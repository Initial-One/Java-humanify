package com.initialone.jhumanify.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.initialone.jhumanify.llm.DocClient;
import com.initialone.jhumanify.llm.RuleDocClient;
import com.initialone.jhumanify.util.Formatter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Injects Javadoc comments for classes and methods (global batching + parallel). */
public class DocAnnotator {

    private final JavaParser parser;
    private final DocClient docClient; // can be RuleDocClient (offline) or LLM client
    private final boolean overwrite;
    private final String lang;
    private final String style;

    // ===== batching & parallel settings (read from System properties with sane defaults) =====
    private final int batchSize      = Math.max(1, parseIntProp("jhumanify.doc.batch",          12));
    private final int llmConcurrent  = Math.max(1, parseIntProp("jhumanify.doc.llmConcurrent",   8));
    private final int llmRetries     = Math.max(0, parseIntProp("jhumanify.doc.llmRetries",      3));
    private final int headLines      = Math.max(0, parseIntProp("jhumanify.doc.headLines",      40));
    private final int tailLines      = Math.max(0, parseIntProp("jhumanify.doc.tailLines",      30));

    // 仅用于打印批次进度
    private final AtomicInteger globalSeq = new AtomicInteger(0);

    public DocAnnotator(DocClient client, boolean overwrite, String lang, String style) {
        ParserConfiguration cfg = new ParserConfiguration();
        this.parser = new JavaParser(cfg);
        this.docClient = client == null ? new RuleDocClient() : client;
        this.overwrite = overwrite;
        this.lang = lang == null ? "zh" : lang;
        this.style = style == null ? "concise" : style;
    }

    public void annotate(List<Path> sourceRoots) throws Exception {
        // 1) 收集 java 文件
        List<Path> javaFiles = new ArrayList<>();
        for (Path root : sourceRoots) {
            try {
                Files.walk(root).filter(p -> p.toString().endsWith(".java")).forEach(javaFiles::add);
            } catch (IOException ioe) {
                System.err.println("[annotate] io " + root + " -> " + ioe.getMessage());
            }
        }
        if (javaFiles.isEmpty()) return;

        // 2) 预解析 + 收集需要注释的节点、生成 snippets，全放到全局 workItems 中
        List<FileJob>   files    = new ArrayList<>();
        List<WorkItem>  workList = new ArrayList<>();
        for (Path p : javaFiles) {
            try {
                var res = parser.parse(p);
                if (res.getResult().isEmpty()) continue;
                CompilationUnit cu = res.getResult().get();

                List<BodyDeclaration<?>> targets = new ArrayList<>();
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(targets::add);
                cu.findAll(EnumDeclaration.class).forEach(targets::add);
                cu.findAll(RecordDeclaration.class).forEach(targets::add);
                cu.findAll(MethodDeclaration.class).forEach(targets::add);
                cu.findAll(ConstructorDeclaration.class).forEach(targets::add);

                List<BodyDeclaration<?>> needDoc = targets.stream().filter(this::needDoc).collect(Collectors.toList());
                if (needDoc.isEmpty()) continue;

                FileJob fj = new FileJob(p, cu, needDoc);
                files.add(fj);

                for (int i = 0; i < needDoc.size(); i++) {
                    String sn = snippetOfTruncated(needDoc.get(i));
                    workList.add(new WorkItem(fj, i, sn));
                }
            } catch (Exception e) {
                System.err.println("[annotate] parse error at " + p + " -> " + e.getMessage());
            }
        }
        if (workList.isEmpty()) {
            // 没有任何需要注释的节点
            Formatter.formatJava(sourceRoots);
            return;
        }

        // 3) 全局切批 + 预先计算 totalBatches（用于日志分母）
        List<List<WorkItem>> batches = slice(workList, batchSize);
        final int totalBatches = batches.size();

        // 4) 全局线程池并发执行所有批次
        ExecutorService es = Executors.newFixedThreadPool(llmConcurrent);
        List<Future<Void>> futures = new ArrayList<>(totalBatches);

        System.out.printf(
                "[annotate] concurrency cap = %d, batchSize = %d, totalBatches = %d%n",
                llmConcurrent, batchSize, totalBatches);

        for (List<WorkItem> batch : batches) {
            futures.add(es.submit(() -> {
                final int seq = globalSeq.incrementAndGet();
                System.out.println("[annotate] LLM batch " + seq + "/" + totalBatches + " items=" + batch.size());
                // 准备本批 snippets
                List<String> snips = new ArrayList<>(batch.size());
                for (WorkItem wi : batch) snips.add(wi.snippet);

                // 调用 LLM（失败则回退），并做数量兜底
                List<String> outs;
                try {
                    outs = withRetryList(llmRetries, 400, () -> docClient.summarizeDocs(snips, lang, style));
                } catch (Exception e) {
                    outs = new RuleDocClient().summarizeDocs(snips, lang, style);
                }
                if (outs == null) outs = Collections.nCopies(snips.size(), "");
                if (outs.size() != snips.size()) {
                    List<String> fixed = new ArrayList<>(snips.size());
                    for (int i = 0; i < snips.size(); i++) fixed.add(i < outs.size() ? outs.get(i) : "");
                    outs = fixed;
                }

                // 回填到各 FileJob 的局部结果
                for (int i = 0; i < batch.size(); i++) {
                    WorkItem wi = batch.get(i);
                    wi.fileJob.comments.set(wi.localIndex, safe(outs.get(i)));
                }
                return null;
            }));
        }

        // 等待所有批次完成
        for (Future<Void> f : futures) {
            try { f.get(); } catch (ExecutionException ee) {
                System.err.println("[annotate] batch failed: " + ee.getCause());
            }
        }
        es.shutdown();

        // 5) 将注释写回到文件
        for (FileJob fj : files) {
            try {
                List<BodyDeclaration<?>> nodes = fj.nodes;
                for (int i = 0; i < nodes.size(); i++) {
                    BodyDeclaration<?> bd = nodes.get(i);
                    String cmt = fj.comments.get(i);
                    if (bd instanceof MethodDeclaration md) {
                        String tags = BehaviorExtractor.renderTags(md, lang);
                        setJavadoc(bd, cmt, tags);
                    } else if (bd instanceof ConstructorDeclaration cd) {
                        String tags = BehaviorExtractor.renderTags(cd, lang);
                        setJavadoc(bd, cmt, tags);
                    } else {
                        setJavadoc(bd, cmt, "");
                    }
                }
                Files.writeString(fj.path, fj.cu.toString(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.err.println("[annotate] write error at " + fj.path + " -> " + e.getMessage());
            }
        }

        // 6) 统一格式化
        Formatter.formatJava(sourceRoots);
    }

    /* ================= helpers ================= */

    private boolean needDoc(BodyDeclaration<?> bd) {
        boolean has = bd.getComment().isPresent() && bd.getComment().get().isJavadocComment();
        return overwrite || !has;
    }

    private String snippetOfTruncated(BodyDeclaration<?> bd) {
        if (bd instanceof MethodDeclaration md) {
            String head = md.getDeclarationAsString();
            String body = md.getBody().map(Object::toString).orElse("{}");
            return head + "\n" + trimCode(body, headLines, tailLines);
        }
        if (bd instanceof ConstructorDeclaration cd) {
            String head = cd.getDeclarationAsString();
            return head + "\n" + trimCode(cd.getBody().toString(), headLines, tailLines);
        }
        if (bd instanceof ClassOrInterfaceDeclaration c) {
            String sig = c.getNameAsString() + " " + c.getExtendedTypes() + " " + c.getImplementedTypes();
            return sig + "\n" + trimCode(c.toString(), headLines, tailLines);
        }
        if (bd instanceof EnumDeclaration e) {
            return e.getNameAsString() + " enum\n" + trimCode(e.toString(), headLines, tailLines);
        }
        if (bd instanceof RecordDeclaration r) {
            return r.getNameAsString() + " record " + r.getParameters() + "\n" + trimCode(r.toString(), headLines, tailLines);
        }
        return trimCode(bd.toString(), headLines, tailLines);
    }

    private static String trimCode(String code, int head, int tail) {
        if (code == null) return "";
        String[] lines = code.split("\\R", -1);
        if (lines.length <= head + tail + 5) return code; // 小文件不截
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(head, lines.length); i++) sb.append(lines[i]).append('\n');
        sb.append("// ... (trimmed) ...\n");
        for (int i = Math.max(lines.length - tail, 0); i < lines.length; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    private void setJavadoc(BodyDeclaration<?> bd, String main, String tags) {
        StringBuilder sb = new StringBuilder();
        sb.append("/**\n");
        for (String line : safe(main).split("\\R")) {
            if (line.isEmpty()) sb.append(" *\n");
            else sb.append(" * ").append(line.trim()).append("\n");
        }
        if (tags != null && !tags.isEmpty()) {
            for (String line : tags.split("\\R")) {
                if (line.isEmpty()) sb.append(" *\n");
                else if (line.startsWith(" *")) sb.append(line).append("\n");
                else sb.append(" * ").append(line).append("\n");
            }
        }
        sb.append(" */");
        String jdoc = sb.toString();

        if (bd instanceof NodeWithJavadoc<?>) {
            ((NodeWithJavadoc<?>) bd).setJavadocComment(jdoc);
        } else {
            bd.setComment(new JavadocComment(jdoc));
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static int parseIntProp(String key, int def) {
        try {
            String v = System.getProperty(key);
            return (v == null || v.isBlank()) ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean isTransient(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.io.EOFException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.ProtocolException
                    || t instanceof javax.net.ssl.SSLException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static List<String> withRetryList(int maxAttempts, long baseBackoffMs,
                                              Callable<List<String>> op) throws Exception {
        int attempts = 0;
        long backoff = baseBackoffMs;
        while (true) {
            try {
                return op.call();
            } catch (Exception e) {
                boolean transientErr = isTransient(e);
                attempts++;
                if (!transientErr || attempts >= Math.max(1, maxAttempts)) throw e;
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, 4000);
            }
        }
    }

    /* ================= data holders ================= */

    static class FileJob {
        final Path path;
        final CompilationUnit cu;
        final List<BodyDeclaration<?>> nodes;
        final List<String> comments; // 与 nodes 对应，初始化为空串

        FileJob(Path path, CompilationUnit cu, List<BodyDeclaration<?>> nodes) {
            this.path = path;
            this.cu = cu;
            this.nodes = nodes;
            this.comments = new ArrayList<>(Collections.nCopies(nodes.size(), ""));
        }
    }

    static class WorkItem {
        final FileJob fileJob;
        final int     localIndex;
        final String  snippet;
        WorkItem(FileJob fj, int localIndex, String snippet) {
            this.fileJob = fj;
            this.localIndex = localIndex;
            this.snippet = snippet;
        }
    }

    // 将 list 按 size 切成多个批次；每个批次是独立 ArrayList（避免 subList 的并发/视图问题）
    private static <T> List<List<T>> slice(List<T> list, int size) {
        List<List<T>> res = new ArrayList<>();
        if (list == null || list.isEmpty() || size <= 0) return res;
        for (int i = 0; i < list.size(); i += size) {
            int to = Math.min(i + size, list.size());
            res.add(new ArrayList<>(list.subList(i, to)));
        }
        return res;
    }
}