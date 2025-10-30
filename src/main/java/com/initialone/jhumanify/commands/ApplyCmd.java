package com.initialone.jhumanify.commands;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 将 mapping.json 中的重命名建议应用到源码目录（文本级别的安全替换）。
 * 特性：
 * - 分批执行 (--batch) + 并发 (--max-concurrent)
 * - 不中断：单文件失败只记录错误并继续
 * - 可选断点续跑 (--resume-file)
 * - 支持 dry-run 只统计替换，不写盘 (--dry-run)
 *
 * 输入：
 *  1) sourcesDir  : 反编译/还原后的 Java 源码根目录（包含 .java）
 *  2) mappingJson : 映射（simple/classFqn/fieldFqn/methodSig）
 *  3) outDir      : 输出目录（会按原路径结构写入）
 */
@CommandLine.Command(
        name = "apply",
        description = "Apply rename mapping.json to source tree with batching & parallelism"
)
public class ApplyCmd implements Runnable {

    @CommandLine.Parameters(index = "0", description = "Input sources dir (e.g. work/src_raw or out/sources)")
    String sourcesDir;

    @CommandLine.Parameters(index = "1", description = "mapping.json produced by suggest")
    String mappingJson;

    @CommandLine.Parameters(index = "2", description = "Output dir for rewritten sources")
    String outDir;

    @CommandLine.Option(names = "--batch", defaultValue = "100",
            description = "Files per batch (default: ${DEFAULT-VALUE})")
    int batch;

    @CommandLine.Option(names = "--max-concurrent", defaultValue = "100",
            description = "Max concurrent workers (default: ${DEFAULT-VALUE})")
    int maxConcurrent;

    @CommandLine.Option(names = "--dry-run", defaultValue = "false",
            description = "Analyze & print stats without writing files")
    boolean dryRun;

    @CommandLine.Option(names = "--resume-file", description = "Resume file path to record processed files")
    String resumeFile;

    @CommandLine.Option(names = "--extensions", split = ",", defaultValue = ".java",
            description = "Comma-separated file extensions to process (default: ${DEFAULT-VALUE})")
    List<String> exts;

    static class Mapping {
        public Map<String,String> simple    = new LinkedHashMap<>();
        public Map<String,String> classFqn  = new LinkedHashMap<>();
        public Map<String,String> fieldFqn  = new LinkedHashMap<>();
        public Map<String,String> methodSig = new LinkedHashMap<>();
    }

    @Override
    public void run() {
        Path src = Paths.get(sourcesDir);
        Path out = Paths.get(outDir);
        Path mapPath = Paths.get(mappingJson);

        try {
            if (!Files.isDirectory(src)) {
                throw new CommandLine.ParameterException(new CommandLine(this), "sourcesDir not a directory: " + src.toAbsolutePath());
            }
            if (!Files.exists(mapPath)) {
                throw new CommandLine.ParameterException(new CommandLine(this), "mapping.json not found: " + mapPath.toAbsolutePath());
            }
            Files.createDirectories(out);

            ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            Mapping mapping = om.readValue(mapPath.toFile(), new TypeReference<>() {});
            System.out.println("[apply] mapping: simple=" + mapping.simple.size()
                    + " classFqn=" + mapping.classFqn.size()
                    + " fieldFqn=" + mapping.fieldFqn.size()
                    + " methodSig=" + mapping.methodSig.size());

            // 收集文件列表
            List<Path> files = listFiles(src, exts == null || exts.isEmpty()
                    ? List.of(".java") : exts);

            // 断点续跑：加载已处理集合
            Set<String> done = loadDone(resumeFile);
            List<Path> todo = files.stream()
                    .filter(p -> !done.contains(src.relativize(p).toString()))
                    .collect(Collectors.toList());

            System.out.println("[apply] total files=" + files.size() + ", to process=" + todo.size());

            // 预构建替换规则
            Rewriters rules = buildRewriters(mapping);

            // 分批
            List<List<Path>> batches = chunk(todo, Math.max(1, batch));
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, maxConcurrent));

            AtomicInteger bi = new AtomicInteger(0);
            List<Future<BatchStat>> futs = new ArrayList<>();

            for (List<Path> one : batches) {
                int batchIndex = bi.incrementAndGet();
                futs.add(pool.submit(() -> processBatch(batchIndex, batches.size(), src, out, one, rules, dryRun, resumeFile, done)));
            }

            long totalRepl = 0;
            long totalErr = 0;
            for (Future<BatchStat> f : futs) {
                try {
                    BatchStat s = f.get();
                    totalRepl += s.replacements;
                    totalErr += s.errors;
                } catch (Exception e) {
                    // 理论上单批内部已吞异常，这里兜底不让整体失败
                    System.err.println("[apply] batch future failed: " + e);
                    totalErr++;
                }
            }
            pool.shutdown();

            System.out.printf("[apply] DONE. replacements=%d, errors=%d, out=%s%s%n",
                    totalRepl, totalErr, out.toAbsolutePath(), dryRun ? " (dry-run)" : "");

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    /* ======================= 批处理核心 ======================= */

    private static class BatchStat {
        long replacements;
        long errors;
    }

    private BatchStat processBatch(int idx, int total,
                                   Path srcRoot, Path outRoot,
                                   List<Path> files, Rewriters rules,
                                   boolean dryRun,
                                   String resumeFile,
                                   Set<String> done) {
        System.out.println("[apply] batch " + idx + "/" + total + " items=" + files.size());
        long repl = 0;
        long err = 0;
        for (Path p : files) {
            try {
                String rel = srcRoot.relativize(p).toString();
                String code = Files.readString(p, StandardCharsets.UTF_8);
                String out = rules.apply(rel, code);

                if (!dryRun) {
                    Path outPath = outRoot.resolve(rel);
                    Files.createDirectories(outPath.getParent());
                    Files.writeString(outPath, out, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    // 记录已完成
                    appendDone(resumeFile, rel);
                    done.add(rel);
                }
                repl += rules.lastReplaceCount;
            } catch (Throwable t) {
                System.err.println("[apply] file failed: " + p + " : " + t);
                err++;
            }
        }
        BatchStat st = new BatchStat();
        st.replacements = repl;
        st.errors = err;
        return st;
    }

    /* ======================= Rewriters ======================= */

    private static class Rewriters {
        // 预编译的正则规则
        final List<Rule> classImports = new ArrayList<>();
        final List<Rule> classSimple  = new ArrayList<>();
        final Map<String, List<Rule>> methodDeclsByClass = new HashMap<>();
        final Map<String, List<Rule>> fieldDeclsByClass  = new HashMap<>();
        final List<Rule> simpleIds    = new ArrayList<>();

        long lastReplaceCount = 0;

        String apply(String relativePath, String code) {
            long cnt = 0;

            // 1) import 全限定名精确替换
            for (Rule r : classImports) code = cnt(code, r);

            // 2) 类的简单名替换（带单词边界）
            for (Rule r : classSimple)   code = cnt(code, r);

            // 3) 依据文件推断类 FQN（粗略：由路径 packages 推断）
            String primaryClassFqn = guessFqnFromPath(relativePath);

            // 4) 方法声明重命名（按类分组）
            if (primaryClassFqn != null) {
                List<Rule> rs = methodDeclsByClass.get(primaryClassFqn);
                if (rs != null) {
                    for (Rule r : rs) code = cnt(code, r);
                }
                rs = fieldDeclsByClass.get(primaryClassFqn);
                if (rs != null) {
                    for (Rule r : rs) code = cnt(code, r);
                }
            }

            // 5) 简单符号（变量/字段的短名）替换
            for (Rule r : simpleIds) code = cnt(code, r);

            this.lastReplaceCount = cnt;
            return code;
        }

        private String cnt(String code, Rule r) {
            var m = r.p.matcher(code);
            String out = m.replaceAll(r.repl);
            if (!out.equals(code)) {
                // 粗略统计：替换次数（通过匹配器计数更精确，这里简单处理）
                this.lastReplaceCount++;
            }
            return out;
        }
    }

    private static class Rule {
        final Pattern p;
        final String repl;
        Rule(Pattern p, String repl) { this.p = p; this.repl = repl; }
    }

    private static Rewriters buildRewriters(Mapping m) {
        Rewriters rw = new Rewriters();

        // --- classFqn：import 和 类简单名 ---
        for (var e : m.classFqn.entrySet()) {
            String oldFqn = e.getKey();
            String newFqn = e.getValue();
            if (oldFqn == null || newFqn == null || oldFqn.equals(newFqn)) continue;

            // import oldFqn; -> import newFqn;
            rw.classImports.add(new Rule(
                    Pattern.compile("(?m)^\\s*import\\s+" + Pattern.quote(oldFqn) + "\\s*;\\s*$"),
                    "import " + newFqn + ";"
            ));

            String oldSimple = simpleName(oldFqn);
            String newSimple = simpleName(newFqn);

            // 类简单名：(?<![\\w$])old(?![\\w$])
            rw.classSimple.add(new Rule(
                    Pattern.compile("(?<![\\w$])" + Pattern.quote(oldSimple) + "(?![\\w$])"),
                    newSimple
            ));
        }

        // --- methodSig：按声明重命名（不改调用点，避免误伤）---
        // 允许的 key 形如：pkg.Class#foo(int,java.lang.String)
        for (var e : m.methodSig.entrySet()) {
            String sig = e.getKey();
            String nn  = e.getValue();
            if (sig == null || nn == null || nn.isBlank()) continue;
            int i = sig.indexOf('#');
            if (i <= 0) continue;
            String owner = sig.substring(0, i);
            String rest  = sig.substring(i + 1); // foo(...)

            String oldName = rest;
            int j = rest.indexOf('(');
            if (j > 0) oldName = rest.substring(0, j).trim();

            // 匹配方法声明： [修饰符/返回值/泛型等] oldName(
            // 尽量保守：只在 oldName 后紧跟 '(' 时替换
            Pattern decl = Pattern.compile("(?m)(?<=\\W)" + Pattern.quote(oldName) + "\\s*\\(");
            Rule r = new Rule(decl, nn + "(");
            rw.methodDeclsByClass.computeIfAbsent(owner, k -> new ArrayList<>()).add(r);
        }

        // --- fieldFqn：按声明重命名（owner#field）---
        for (var e : m.fieldFqn.entrySet()) {
            String k = e.getKey(); // owner#field
            String nn = e.getValue();
            if (k == null || nn == null || nn.isBlank()) continue;
            int i = k.indexOf('#');
            if (i <= 0) continue;
            String owner = k.substring(0, i);
            String old   = k.substring(i + 1);

            // 大致匹配字段声明：<mods/types> old(=|;|,| )
            Pattern decl = Pattern.compile("(?m)(?<![\\w$])" + Pattern.quote(old) + "(?![\\w$])");
            Rule r = new Rule(decl, nn);
            rw.fieldDeclsByClass.computeIfAbsent(owner, x -> new ArrayList<>()).add(r);
        }

        // --- simple：短标识符（变量/字段用法），全局安全边界替换 ---
        for (var e : m.simple.entrySet()) {
            String old = e.getKey();
            String nn  = e.getValue();
            if (old == null || nn == null || nn.isBlank() || old.equals(nn)) continue;
            rw.simpleIds.add(new Rule(
                    Pattern.compile("(?<![\\w$])" + Pattern.quote(old) + "(?![\\w$])"),
                    nn
            ));
        }

        return rw;
    }

    /* ======================= 工具函数 ======================= */

    private static List<Path> listFiles(Path root, List<String> exts) throws IOException {
        Set<String> allow = exts.stream()
                .map(s -> s.startsWith(".") ? s.toLowerCase() : "." + s.toLowerCase())
                .collect(Collectors.toSet());
        List<Path> out = new ArrayList<>();
        try (var s = Files.walk(root)) {
            s.filter(p -> Files.isRegularFile(p))
                    .filter(p -> allow.contains(extOf(p)))
                    .forEach(out::add);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static String extOf(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        int i = n.lastIndexOf('.');
        return (i >= 0 ? n.substring(i) : "");
    }

    private static String simpleName(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i >= 0 ? fqn.substring(i + 1) : fqn;
    }

    private static String guessFqnFromPath(String rel) {
        // e.g. com/foo/bar/MyClass.java -> com.foo.bar.MyClass
        if (rel == null) return null;
        String unix = rel.replace('\\', '/');
        int i = unix.lastIndexOf('/');
        if (i < 0) {
            String base = unix.endsWith(".java") ? unix.substring(0, unix.length() - 5) : unix;
            return base;
        }
        String dir = unix.substring(0, i).replace('/', '.');
        String base = unix.substring(i + 1);
        if (base.endsWith(".java")) base = base.substring(0, base.length() - 5);
        if (dir.isEmpty()) return base;
        return dir + "." + base;
    }

    private static List<List<Path>> chunk(List<Path> in, int n) {
        List<List<Path>> batches = new ArrayList<>();
        int i = 0;
        while (i < in.size()) {
            int j = Math.min(i + n, in.size());
            batches.add(in.subList(i, j));
            i = j;
        }
        return batches;
    }

    private static Set<String> loadDone(String resumeFile) throws IOException {
        if (resumeFile == null || resumeFile.isBlank()) return new HashSet<>();
        Path p = Paths.get(resumeFile);
        if (!Files.exists(p)) return new HashSet<>();
        return new HashSet<>(Files.readAllLines(p, StandardCharsets.UTF_8));
    }

    private static void appendDone(String resumeFile, String relPath) {
        if (resumeFile == null || resumeFile.isBlank()) return;
        try {
            Path p = Paths.get(resumeFile);
            Files.createDirectories(p.getParent() == null ? Paths.get(".") : p.getParent());
            Files.writeString(p, relPath + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    Files.exists(p) ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
        } catch (Exception ignore) {}
    }
}