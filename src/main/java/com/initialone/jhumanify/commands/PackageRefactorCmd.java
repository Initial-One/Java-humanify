package com.initialone.jhumanify.commands;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.initialone.jhumanify.llm.LlmOptions;
import okhttp3.*;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@CommandLine.Command(
        name = "package-refactor",
        description = "Rename obfuscated package/folder names via LLM or heuristic and update 'package'/'import'."
)
public class PackageRefactorCmd implements Runnable {

    @CommandLine.Mixin
    LlmOptions llm;

    @CommandLine.Option(names="--src", required = true, description = "Java source root (the directory that contains packages)")
    String srcDir;

    @CommandLine.Option(names="--mode", defaultValue = "llm", description = "llm|heuristic (default: ${DEFAULT-VALUE})")
    String mode;

    @CommandLine.Option(names="--skip-pattern", description = "Regex to skip packages, e.g. ^(androidx?|com\\.google|org)\\b")
    String skipPattern;

    @CommandLine.Option(names="--leaf-only", defaultValue = "true", description = "Rename only the leaf segment of the package path (default: ${DEFAULT-VALUE})")
    boolean leafOnly;

    @CommandLine.Option(names="--max-candidates", defaultValue = "1000", description = "Max leaf directories to consider (default: ${DEFAULT-VALUE})")
    int maxCandidates;

    // 批量 LLM（每批多少个包）
    @CommandLine.Option(names="--pkg-batch", defaultValue = "100", description = "How many package candidates per LLM batch (default: ${DEFAULT-VALUE})")
    int pkgBatch;

    private static final ObjectMapper OM = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    @Override
    public void run() {
        Path root = Paths.get(srcDir);
        try {
            Path javaRoot = root;//resolveJavaRoot(root);
            if (!Files.isDirectory(javaRoot)) {
                throw new IllegalStateException("Java root not found: " + javaRoot.toAbsolutePath());
            }

            Pattern skip;
            if (skipPattern != null && !skipPattern.isBlank()) {
                skip = Pattern.compile(skipPattern);
            } else {
                // 默认跳过常见第三方包
                skip = Pattern.compile("^(androidx?|com\\.google|org|kotlinx?|java|javax|sun)\\b");
            }

            // 1) 找到叶子目录（包含 .java 的目录）
            List<Path> leafDirs = findLeafJavaDirs(javaRoot);
            if (leafDirs.isEmpty()) {
                System.out.println("[package-refactor] no java dirs found under: " + javaRoot);
                return;
            }

            // 2) 过滤出“可疑”目录
            List<DirInfo> candidates = new ArrayList<>();
            for (Path dir : leafDirs) {
                String pkg = relPkg(javaRoot, dir);
                if (skip.matcher(pkg).find()) continue;

                String leaf = dir.getFileName().toString();
                if (looksObfuscated(leaf)) {
                    candidates.add(new DirInfo(dir, pkg, leaf));
                }
            }
            if (candidates.isEmpty()) {
                System.out.println("[package-refactor] no suspicious packages to rename.");
                return;
            }
            if (candidates.size() > maxCandidates) {
                candidates = candidates.subList(0, maxCandidates);
            }
            System.out.println("[package-refactor] candidates=" + candidates.size());

            // 3) 为每个候选生成新段名
            Map<Path, String> newLeafName = new LinkedHashMap<>();
            if ("llm".equalsIgnoreCase(mode) && !"dummy".equalsIgnoreCase(llm.provider)) {
                // 分批调用 LLM
                List<List<DirInfo>> batches = slice(candidates, Math.max(1, pkgBatch));
                int seq = 0;
                for (List<DirInfo> batch : batches) {
                    seq++;
                    System.out.println("[package-refactor] LLM batch " + seq + "/" + batches.size() + " items=" + batch.size());
                    Map<String, String> one = llmSuggest(javaRoot, batch);
                    // one: key = abs path string；value = new leaf
                    for (DirInfo di : batch) {
                        String k = di.dir.toAbsolutePath().toString();
                        String v = sanitizeSegment(one.getOrDefault(k, heuristicName(javaRoot, di)));
                        newLeafName.put(di.dir, v);
                    }
                }
            } else {
                for (DirInfo di : candidates) {
                    newLeafName.put(di.dir, sanitizeSegment(heuristicName(javaRoot, di)));
                }
            }

            // 4) 解决重名冲突
            Map<Path, Path> movePlan = new LinkedHashMap<>();
            Set<String> siblingTaken = new HashSet<>();
            for (Map.Entry<Path, String> e : newLeafName.entrySet()) {
                Path oldDir = e.getKey();
                String base = e.getValue();
                if (base == null || base.isBlank()) base = "pkg";
                base = sanitizeSegment(base);

                Path parent = oldDir.getParent();
                String target = base;
                int n = 2;
                while (Files.exists(parent.resolve(target)) || !siblingTaken.add(parent.resolve(target).toString())) {
                    target = base + "_" + n++;
                }
                movePlan.put(oldDir, parent.resolve(target));
            }

            // 5) 生成 oldPkg -> newPkg 映射（用于更新 imports）
            Map<String, String> pkgMap = new LinkedHashMap<>();
            for (Map.Entry<Path, Path> e : movePlan.entrySet()) {
                String oldPkg = relPkg(javaRoot, e.getKey());
                String newPkg = relPkg(javaRoot, e.getValue());
                if (!Objects.equals(oldPkg, newPkg)) {
                    pkgMap.put(oldPkg, newPkg);
                }
            }

            if (pkgMap.isEmpty()) {
                System.out.println("[package-refactor] nothing to rename after resolving conflicts.");
                return;
            }

            // 6) 执行重命名（叶子目录）
            for (Map.Entry<Path, Path> e : movePlan.entrySet()) {
                Path from = e.getKey();
                Path to   = e.getValue();
                if (!Objects.equals(from, to)) {
                    Files.createDirectories(to.getParent());
                    System.out.println("[package-refactor] MOVE " + from + "  →  " + to);
                    try {
                        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException amnse) {
                        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            // 7) 重写所有 .java 文件：package 行按新路径写；import 做映射替换
            List<Path> allJava = listAllJava(javaRoot);
            for (Path f : allJava) {
                String content = Files.readString(f, StandardCharsets.UTF_8);
                // package 行重写：
                String expected = relPkg(javaRoot, f.getParent());
                content = content.replaceFirst(
                        "(?m)^\\s*package\\s+[^;]+;",
                        "package " + expected + ";"
                );
                // import 替换：
                for (Map.Entry<String,String> m : pkgMap.entrySet()) {
                    String oldPkg = Pattern.quote(m.getKey());
                    String newPkg = m.getValue();
                    content = content.replaceAll("(?m)^\\s*import\\s+" + oldPkg + "\\.", "import " + newPkg + ".");
                }
                Files.writeString(f, content, StandardCharsets.UTF_8);
            }

            System.out.println("[package-refactor] done. renamed=" + pkgMap.size());

        } catch (Exception e) {
            System.err.println("[package-refactor] failed: " + e.getMessage());
            throw new CommandLine.ExecutionException(new CommandLine(this), e.getMessage(), e);
        }
    }

    /* ================= helpers ================= */

    private static Path resolveJavaRoot(Path src) throws IOException {
        Path candidate = src.resolve("sources");
        if (Files.isDirectory(candidate)) return candidate;
        // 若 src 自身包含 .java 则直接用它
        try (var s = Files.walk(src, 1)) {
            boolean hasJava = s.anyMatch(p -> p.toString().endsWith(".java"));
            if (hasJava) return src;
        }
        // 否则返回第一个包含 .java 的子目录
        try (var s = Files.walk(src, 2)) {
            return s.filter(Files::isDirectory)
                    .filter(d -> {
                        try (var s2 = Files.walk(d, 1)) {
                            return s2.anyMatch(p -> p.toString().endsWith(".java"));
                        } catch (IOException e) { return false; }
                    })
                    .findFirst().orElse(src);
        }
    }

    // 替换原来的 listAllJava 方法
    private static List<Path> listAllJava(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        // 用 FileVisitor，任何读取失败都继续，不让遍历抛出 NoSuchFileException
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) out.add(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 目录或文件在遍历过程中被改名/删除 -> 忽略继续
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                // 目录遍历完或失败都继续
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    private static List<Path> findLeafJavaDirs(Path root) throws IOException {
        // 叶子目录：自身含 .java，且子目录不再含 .java
        Map<Path, Boolean> hasJava = new HashMap<>();
        try (var s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> hasJava.put(p.getParent(), true));
        }
        List<Path> leaf = new ArrayList<>();
        for (Path dir : hasJava.keySet()) {
            boolean childHas = false;
            try (var s = Files.walk(dir, 1)) {
                for (Path sub : (Iterable<Path>) s::iterator) {
                    if (Files.isDirectory(sub) && !sub.equals(dir)) {
                        try (var s2 = Files.walk(sub, 1)) {
                            boolean subHas = s2.anyMatch(x -> x.toString().endsWith(".java"));
                            if (subHas) { childHas = true; break; }
                        }
                    }
                }
            }
            if (!childHas) leaf.add(dir);
        }
        leaf.sort(Comparator.comparing(Path::toString));
        return leaf;
    }

    private static String relPkg(Path root, Path dir) {
        Path rel = root.relativize(dir);
        String joined = rel.toString().replace(FileSystems.getDefault().getSeparator(), ".");
        // 统一小写（保持合法包名）
        return Arrays.stream(joined.split("\\."))
                .map(s -> s.replaceAll("[^a-zA-Z0-9]", ""))
                .map(String::toLowerCase)
                .collect(Collectors.joining("."));
    }

    private static boolean looksObfuscated(String leaf) {
        String s = leaf.toLowerCase(Locale.ROOT);
        // a, a1, b3, c8, aa, ab2, ui73, controls18, e9...
        if (s.matches("^[a-z]{1,2}\\d*$")) return true;
        if (s.matches("^[a-z]\\d+$")) return true;
        if (s.matches("^ui\\d+$")) return true;
        if (s.matches("^controls\\d+$")) return true;
        if (s.length() <= 2 && s.matches("^[a-z]+$")) return true;
        return false;
    }

    private static String heuristicName(Path javaRoot, DirInfo di) throws IOException {
        // 从类名/文件名提取语义 token
        List<String> names = listClassLikeNames(di.dir, 40);
        String hint = String.join(" ", names);
        // 简单启发：取出现频率高的前缀词
        String token = topToken(hint);
        if (token == null || token.length() < 3) token = "module";
        return token;
    }

    private static List<String> listClassLikeNames(Path dir, int limit) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .limit(limit)
                    .map(p -> p.getFileName().toString().replace(".java", ""))
                    .collect(Collectors.toList());
        }
    }

    private static String topToken(String text) {
        if (text == null) return null;
        String[] parts = text.split("[^A-Za-z0-9]+");
        Map<String,Integer> cnt = new HashMap<>();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            String t = p.toLowerCase(Locale.ROOT);
            if (t.length() < 3) continue;
            cnt.put(t, cnt.getOrDefault(t, 0) + 1);
        }
        return cnt.entrySet().stream()
                .sorted((a,b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    private static String sanitizeSegment(String s) {
        if (s == null) return "pkg";
        String x = s.replaceAll("[^A-Za-z0-9]+", "");
        if (x.isBlank()) x = "pkg";
        x = x.toLowerCase(Locale.ROOT);
        if (x.length() > 24) x = x.substring(0, 24);
        return x;
    }

    private static <T> List<List<T>> slice(List<T> list, int size) {
        List<List<T>> res = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            res.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return res;
    }

    /* ============== LLM 调用（OpenAI 兼容 / DeepSeek / 本地 OpenAI 兼容） ============== */

    private Map<String, String> llmSuggest(Path javaRoot, List<DirInfo> batch) {
        // 构造提示：每个目录给出绝对路径键、包名、样本类名
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert Android/Java refactoring assistant.\n")
                .append("Task: For each folder below, propose a concise semantic Java PACKAGE SEGMENT (one word, lowercase letters/digits only),\n")
                .append("representing that folder's meaning (e.g., splash, login, onboarding, mainview, settings, chart, image, network).\n")
                .append("Rules:\n")
                .append("- Output STRICT JSON with an object mapping absoluteFolderPath -> {\"newSegment\":\"<lowercase>\"}.\n")
                .append("- newSegment MUST be lowercase letters/digits only, <= 24 chars, NO spaces/underscores/dashes.\n")
                .append("- Prefer domain terms seen in class names.\n")
                .append("- If unclear, guess a general term (e.g., module, ui, core, common).\n\n");

        for (DirInfo di : batch) {
            try {
                List<String> names = listClassLikeNames(di.dir, 40);
                sb.append("FOLDER: ").append(di.dir.toAbsolutePath()).append("\n");
                sb.append("PACKAGE: ").append(di.pkg).append("\n");
                sb.append("CLASSES: ").append(String.join(", ", names)).append("\n\n");
            } catch (IOException ignored) {}
        }

        String sys = "You are a helpful refactoring assistant.";
        String content = chat(sys, sb.toString());

        // 解析 JSON
        try {
            Map<String, Map<String,String>> obj = OM.readValue(content.getBytes(StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Map<String,String>>>(){});
            Map<String,String> out = new HashMap<>();
            for (var e : obj.entrySet()) {
                String key = e.getKey();
                String seg = e.getValue() == null ? null : e.getValue().get("newSegment");
                out.put(key, sanitizeSegment(seg));
            }
            return out;
        } catch (Exception e) {
            // 兜底：全部用 heuristic
            Map<String,String> out = new HashMap<>();
            for (DirInfo di : batch) {
                try {
                    out.put(di.dir.toAbsolutePath().toString(), sanitizeSegment(heuristicName(javaRoot, di)));
                } catch (IOException ex) {
                    out.put(di.dir.toAbsolutePath().toString(), "module");
                }
            }
            return out;
        }
    }

    private String chat(String system, String user) {
        OkHttpClient http = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .readTimeout(java.time.Duration.ofSeconds(Math.max(60, llm.timeoutSec)))
                .writeTimeout(java.time.Duration.ofSeconds(Math.max(60, llm.timeoutSec)))
                .callTimeout(java.time.Duration.ofSeconds(Math.max(90, llm.timeoutSec + 30)))
                .build();

        final String base = llmBaseUrl();
        String model = resolveModelForProvider(); // <- 根据 --provider / 环境变量 / 默认值 确认模型

        // payload（对 user 做控制字符清理）
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", sanitizeForJson(user))
        ));
        payload.put("temperature", 0.2);
        payload.put("max_tokens", 800);

        Request.Builder rb = new Request.Builder()
                .url(base + "/v1/chat/completions")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .post(RequestBody.create(toJson(payload), JSON));

        String apiKey = resolveApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }

        // 首选模型尝试 +（可选）DeepSeek 的候选回退
        String[] deepseekFallbacks = new String[] { "deepseek-v3", "deepseek-reasoner", "deepseek-coder" };
        boolean isDeepseek = "deepseek".equalsIgnoreCase(llm.provider) || base.contains("deepseek.com");

        // 对当前模型先做最多 3 次 429/5xx 重试
        try {
            return attemptChat(http, rb, model, 3);
        } catch (RuntimeException re) {
            String msg = String.valueOf(re.getMessage()).toLowerCase(Locale.ROOT);
            boolean modelNotFound = msg.contains("model") && msg.contains("not found") || msg.contains("404");
            if (isDeepseek && modelNotFound && (llm.model == null || llm.model.isBlank())) {
                // 自动尝试候选
                for (String alt : deepseekFallbacks) {
                    if (alt.equalsIgnoreCase(model)) continue;
                    System.err.println("[package-refactor] model '" + model + "' not found, fallback try: " + alt);
                    payload.put("model", alt);
                    Request reqAlt = rb.post(RequestBody.create(toJson(payload), JSON)).build();
                    try (Response resp = http.newCall(reqAlt).execute()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        if (!resp.isSuccessful()) {
                            continue; // 尝试下一个
                        }
                        String content = extractContent(body);
                        String json = extractJson(content);
                        return json;
                    } catch (Exception ignore) {
                        // 继续下一候选
                    }
                }
            }
            throw re; // 非模型问题或回退失败——抛出
        }
    }

    private String attemptChat(OkHttpClient http, Request.Builder rb, String model, int maxAttempts) {
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            Request req = rb.build();
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    // 429/5xx 退避重试
                    if ((resp.code() == 429 || resp.code() >= 500) && attempt < maxAttempts) {
                        long backoff = (long) (500L * Math.pow(2, attempt - 1) + new Random().nextInt(300));
                        System.err.printf("[package-refactor] HTTP %d on model=%s, backoff %dms, attempt %d/%d%n",
                                resp.code(), model, backoff, attempt, maxAttempts);
                        sleep(backoff);
                        continue;
                    }
                    throw new RuntimeException("HTTP " + resp.code() + ": " + safe(body));
                }
                String content = extractContent(body);
                return extractJson(content);
            } catch (Exception e) {
                if (attempt >= maxAttempts) {
                    throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
                }
                long backoff = (long) (500L * Math.pow(2, attempt - 1) + new Random().nextInt(300));
                System.err.printf("[package-refactor] network error on model=%s, backoff %dms, attempt %d/%d: %s%n",
                        model, backoff, attempt, maxAttempts, e.toString());
                sleep(backoff);
            }
        }
        throw new RuntimeException("LLM call failed");
    }

    private String llmBaseUrl() {
        // 1) 用户显式传入 endpoint：始终尊重
        if (llm.endpoint != null && !llm.endpoint.isBlank()) {
            return stripV1(trimSlash(llm.endpoint));
        }

        // 2) 根据 provider 选择默认基地址
        if ("deepseek".equalsIgnoreCase(llm.provider)) {
            return "https://api.deepseek.com";
        }
        if ("local".equalsIgnoreCase(llm.provider)) {
            // 本地 OpenAI 兼容默认值
            return "http://localhost:11434";
        }

        // 3) openai（或未指定）：支持 OPENAI_BASE_URL 覆盖
        String base = System.getenv("OPENAI_BASE_URL");
        if (base == null || base.isBlank()) base = "https://api.openai.com";
        return stripV1(trimSlash(base));
    }

    private String resolveApiKey() {
        if ("deepseek".equalsIgnoreCase(llm.provider)) {
            String k = System.getenv("DEEPSEEK_API_KEY");
            return (k != null && !k.isBlank()) ? k : null;
        }
        if ("openai".equalsIgnoreCase(llm.provider) || llm.provider == null || llm.provider.isBlank()) {
            String k = System.getenv("OPENAI_API_KEY");
            return (k != null && !k.isBlank()) ? k : null;
        }
        // local 一般不需要 key；有些代理需要可自行注入
        return null;
    }

    private static String trimSlash(String base) {
        if (base.endsWith("/")) return base.substring(0, base.length() - 1);
        return base;
    }

    private static String stripV1(String base) {
        // 把尾部 /v1 去掉，确保调用处统一拼 /v1/chat/completions
        if (base.endsWith("/v1")) return base.substring(0, base.length() - 3);
        return base;
    }

    /**
     * 根据 --provider / 环境变量 / 默认值，解析模型名：
     * 优先级： --model > 环境变量(OPENAI_MODEL/DEEPSEEK_MODEL) > provider 默认。
     */
    private String resolveModelForProvider() {
        if (llm.model != null && !llm.model.isBlank()) return llm.model;

        if ("deepseek".equalsIgnoreCase(llm.provider)) {
            String env = System.getenv("DEEPSEEK_MODEL");
            if (env != null && !env.isBlank()) return env;
            // 默认给一个通用的候选
            return "deepseek-v3";
        }

        // openai / local-openai-compat
        String env = System.getenv("OPENAI_MODEL");
        if (env != null && !env.isBlank()) return env;
        return "gpt-4o-mini";
    }

    private static String extractContent(String body) throws IOException {
        var root = OM.readTree(body);
        var choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) throw new IOException("no choices");
        return choices.get(0).path("message").path("content").asText("");
    }

    private static String extractJson(String s) {
        // 去掉 ```json ... ``` 包裹
        String cleaned = s;
        int fence = cleaned.indexOf("```");
        if (fence >= 0) {
            int fence2 = cleaned.indexOf("```", fence + 3);
            if (fence2 > fence) cleaned = cleaned.substring(fence + 3, fence2);
        }
        int i = cleaned.indexOf('{'), j = cleaned.lastIndexOf('}');
        if (i >= 0 && j > i) return cleaned.substring(i, j + 1);
        return cleaned.trim();
    }

    private static byte[] toJson(Object o) {
        try { return OM.writeValueAsBytes(o); }
        catch (Exception e) { return "{}".getBytes(StandardCharsets.UTF_8); }
    }

    private static String sanitizeForJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int idx = 0; idx < s.length(); idx++) {
            char c = s.charAt(idx);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(c);
            } else if (c < 0x20) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String safe(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }

    /* ================= data ================= */

    static class DirInfo {
        final Path dir;
        final String pkg;
        final String leaf;
        DirInfo(Path dir, String pkg, String leaf) {
            this.dir = dir;
            this.pkg = pkg;
            this.leaf = leaf;
        }
    }
}