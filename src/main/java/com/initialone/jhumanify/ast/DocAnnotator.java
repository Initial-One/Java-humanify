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

/**
 * 把类 / 方法 / 构造器等节点加上 Javadoc 注释。
 *
 * - 会把同一批（当前 root 目录下）的所有待注释节点收集成 snippets，丢给 DocClient(LLM/Rule) 批量总结
 * - LLM 调用是并发 + 重试
 * - 全部注释写回后，再统一格式化源码
 *
 * 注意：AnnotateCmd 现在会把超大项目切成多批小目录来调用 DocAnnotator.annotate()，
 * 所以这里在内存里处理的是“这一小批目录的全部 .java”，不会再吃爆内存。
 */
public class DocAnnotator {

    private final JavaParser parser;
    private final DocClient docClient; // 可以是 RuleDocClient(离线) 或真正的 LLM client
    private final String lang;
    private final String style;
    private final boolean overwrite;

    // ===== batching & parallel settings (从 System properties 里读，提供合理默认) =====
    private final int batchSize      = Math.max(1, parseIntProp("jhumanify.doc.batch",          12));
    private final int llmConcurrent  = Math.max(1, parseIntProp("jhumanify.doc.llmConcurrent",   8));
    private final int llmRetries     = Math.max(0, parseIntProp("jhumanify.doc.llmRetries",      3));
    private final int headLines      = Math.max(0, parseIntProp("jhumanify.doc.headLines",      40));
    private final int tailLines      = Math.max(0, parseIntProp("jhumanify.doc.tailLines",      30));

    // 仅用于打印批次进度
    private final AtomicInteger globalSeq = new AtomicInteger(0);

    public DocAnnotator(DocClient client, String lang, String style) {
        this(client, /*overwrite*/ true, lang, style); // 默认允许覆盖已有注释
    }

    public DocAnnotator(DocClient client, boolean overwrite, String lang, String style) {
        ParserConfiguration cfg = new ParserConfiguration();
        this.parser = new JavaParser(cfg);
        this.docClient = (client == null) ? new RuleDocClient() : client;
        this.overwrite = overwrite;
        this.lang = (lang == null) ? "zh" : lang;
        this.style = (style == null) ? "concise" : style;
    }

    /** 如果 overwrite=false，则已有 Javadoc 的节点就跳过 */
    private boolean needDoc(BodyDeclaration<?> bd) {
        boolean has = bd.getComment().isPresent() && bd.getComment().get().isJavadocComment();
        return overwrite || !has;
    }

    /**
     * 主入口：
     * - root: 这一批要处理的源码根目录（AnnotateCmd会把大项目拆成很多小 batch，每个 batch 有自己的临时 root）
     */
    public void annotate(Path root) throws Exception {
        // 1) 收集该 root 下的所有 .java
        List<Path> javaFiles = new ArrayList<>();
        try {
            Files.walk(root)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(javaFiles::add);
        } catch (IOException ioe) {
            System.err.println("[annotate] io " + root + " -> " + ioe.getMessage());
        }
        if (javaFiles.isEmpty()) {
            return;
        }

        // 2) 解析这些文件，挑出需要补注释的节点，组装 snippet 列表
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

                List<BodyDeclaration<?>> needDocNodes = targets.stream()
                        .filter(this::needDoc)
                        .collect(Collectors.toList());
                if (needDocNodes.isEmpty()) continue;

                FileJob fj = new FileJob(p, cu, needDocNodes);
                files.add(fj);

                for (int i = 0; i < needDocNodes.size(); i++) {
                    String sn = snippetOfTruncated(needDocNodes.get(i));
                    workList.add(new WorkItem(fj, i, sn));
                }
            } catch (Exception e) {
                System.err.println("[annotate] parse error at " + p + " -> " + e.getMessage());
            }
        }

        if (workList.isEmpty()) {
            // 没有需要新注释的节点，但我们还是想让这一批目录格式化一下
            Formatter.formatJava(root);
            return;
        }

        // 3) 把所有待总结的 WorkItem 切成全局批次
        List<List<WorkItem>> batches = slice(workList, batchSize);
        final int totalBatches = batches.size();

        // 4) 用线程池并发跑这些批次，调用 LLM 生成注释文本
        ExecutorService es = Executors.newFixedThreadPool(llmConcurrent);
        List<Future<Void>> futures = new ArrayList<>(totalBatches);

        System.out.printf(
                "[annotate] blocks = %d, batches = %d%n",
                workList.size(), totalBatches
        );

        for (List<WorkItem> batch : batches) {
            futures.add(es.submit(() -> {
                final int seq = globalSeq.incrementAndGet();
                System.out.println("[annotate] LLM batch " + seq + "/" + totalBatches +
                        " items=" + batch.size());

                // snippets 准备
                List<String> snips = new ArrayList<>(batch.size());
                for (WorkItem wi : batch) {
                    snips.add(wi.snippet);
                }

                // 调用 LLM (或 RuleDocClient 兜底)，并做长度兜底
                List<String> outs;
                try {
                    outs = withRetryList(
                            llmRetries,
                            400,
                            () -> docClient.summarizeDocs(snips, lang, style)
                    );
                } catch (Exception e) {
                    outs = new RuleDocClient().summarizeDocs(snips, lang, style);
                }
                if (outs == null) outs = Collections.nCopies(snips.size(), "");

                if (outs.size() != snips.size()) {
                    List<String> fixed = new ArrayList<>(snips.size());
                    for (int i = 0; i < snips.size(); i++) {
                        fixed.add(i < outs.size() ? outs.get(i) : "");
                    }
                    outs = fixed;
                }

                // 把每条注释文本写回到对应的 FileJob.comments[i]
                for (int i = 0; i < batch.size(); i++) {
                    WorkItem wi = batch.get(i);
                    wi.fileJob.comments.set(wi.localIndex, safe(outs.get(i)));
                }
                return null;
            }));
        }

        // 5) 等待所有批次完成
        for (Future<Void> f : futures) {
            try {
                f.get();
            } catch (ExecutionException ee) {
                System.err.println("[annotate] batch failed: " + ee.getCause());
            }
        }
        es.shutdown();

        // 6) 把注释写回源码文件
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
                Files.writeString(
                        fj.path,
                        fj.cu.toString(),
                        StandardCharsets.UTF_8
                );
            } catch (Exception e) {
                System.err.println("[annotate] write error at " + fj.path + " -> " + e.getMessage());
            }
        }

        // 7) 对这一批目录整体跑一次格式化
        Formatter.formatJava(root);
    }

    /* ================= helpers ================= */

    /** 为某个 AST 节点构造截断后的 snippet，发给 LLM 作为上下文 */
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
            String sig = c.getNameAsString() + " " +
                    c.getExtendedTypes() + " " +
                    c.getImplementedTypes();
            return sig + "\n" + trimCode(c.toString(), headLines, tailLines);
        }
        if (bd instanceof EnumDeclaration e) {
            return e.getNameAsString() + " enum\n" +
                    trimCode(e.toString(), headLines, tailLines);
        }
        if (bd instanceof RecordDeclaration r) {
            return r.getNameAsString() + " record " + r.getParameters() + "\n" +
                    trimCode(r.toString(), headLines, tailLines);
        }
        return trimCode(bd.toString(), headLines, tailLines);
    }

    /** 只保留前 head 行 + 后 tail 行，中间折叠成 // ... (trimmed) ... */
    private static String trimCode(String code, int head, int tail) {
        if (code == null) return "";
        String[] lines = code.split("\\R", -1);
        if (lines.length <= head + tail + 5) return code; // 很短就不截断
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(head, lines.length); i++) {
            sb.append(lines[i]).append('\n');
        }
        sb.append("// ... (trimmed) ...\n");
        for (int i = Math.max(lines.length - tail, 0); i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /** 给某个类/方法/构造器节点写入 JavadocComment */
    private void setJavadoc(BodyDeclaration<?> bd, String main, String tags) {
        StringBuilder sb = new StringBuilder();
        sb.append("/**\n");
        for (String line : safe(main).split("\\R")) {
            if (line.isEmpty()) {
                sb.append(" *\n");
            } else {
                sb.append(" * ").append(line.trim()).append("\n");
            }
        }
        if (tags != null && !tags.isEmpty()) {
            for (String line : tags.split("\\R")) {
                if (line.isEmpty()) {
                    sb.append(" *\n");
                } else if (line.startsWith(" *")) {
                    sb.append(line).append("\n");
                } else {
                    sb.append(" * ").append(line).append("\n");
                }
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

    private static String safe(String s) {
        return s == null ? "" : s;
    }

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

    /**
     * 调 LLM 带重试。网络类错误会指数退避重试，最多 llmRetries 次。
     */
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
                if (!transientErr || attempts >= Math.max(1, maxAttempts)) {
                    throw e;
                }
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, 4000);
            }
        }
    }

    /* ================= data holders ================= */

    /** 每个源文件的上下文：AST、待注释节点列表、以及并行填充的注释文本 */
    static class FileJob {
        final Path path;
        final CompilationUnit cu;
        final List<BodyDeclaration<?>> nodes;
        final List<String> comments; // 与 nodes 对应

        FileJob(Path path, CompilationUnit cu, List<BodyDeclaration<?>> nodes) {
            this.path = path;
            this.cu = cu;
            this.nodes = nodes;
            this.comments = new ArrayList<>(Collections.nCopies(nodes.size(), ""));
        }
    }

    /** WorkItem 表示“某个 FileJob 里的第 i 个节点 + 它的 snippet” */
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

    /**
     * 把大列表切成一批批 ArrayList，避免 subList 的视图问题也避免过大 batch。
     */
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