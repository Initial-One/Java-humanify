package com.initialone.jhumanify.commands;

import com.initialone.jhumanify.llm.LlmOptions;
import com.initialone.jhumanify.model.RenameSuggestion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@CommandLine.Command(
        name = "suggest",
        description = "Generate rename mapping from snippets.json via OpenAI / DeepSeek / Local or heuristic"
)
public class SuggestCmd implements Runnable {
    @CommandLine.Mixin
    LlmOptions llm;

    int codeLines = 50;

    @CommandLine.Parameters(index = "0", description = "Input snippets.json (from analyze)")
    String snippetsJson;

    @CommandLine.Parameters(index = "1", description = "Output mapping.json")
    String mappingJson;

    @CommandLine.Option(names = "--token-budget", defaultValue = "9000",
            description = "Approx tokens per LLM batch (rough estimate)")
    int tokenBudget;

    @CommandLine.Option(names = "--retries", defaultValue = "6",
            description = "Transient network retries for each LLM call (default: ${DEFAULT-VALUE})")
    int retries;

    @CommandLine.Option(names = "--retry-initial-ms", defaultValue = "800",
            description = "Initial backoff for retry in milliseconds")
    long retryInitialMs;

    @CommandLine.Option(names = "--retry-multiplier", defaultValue = "1.8",
            description = "Exponential backoff multiplier")
    double retryMultiplier;

    @CommandLine.Option(names = "--retry-jitter-ms", defaultValue = "300",
            description = "Random jitter added to backoff in milliseconds")
    long retryJitterMs;

    /* ======== 输入数据结构 ======== */
    static class Snippet {
        public String file;
        public String pkg;
        public String classFqn;
        public String className;
        public String methodName;
        public List<String> paramTypes;
        public String qualifiedSignature;
        public String decl;
        public String code;
        public List<String> strings = new ArrayList<>();
    }

    /** 输出映射（精确键） */
    public static class Mapping {
        public Map<String,String> simple    = new LinkedHashMap<>();
        public Map<String,String> classFqn  = new LinkedHashMap<>();
        public Map<String,String> fieldFqn  = new LinkedHashMap<>();
        public Map<String,String> methodSig = new LinkedHashMap<>();
    }

    /* ======== 键上锁（只允许这些键） ======== */
    static class Allowed {
        Set<String> classKeys  = new LinkedHashSet<>();
        Set<String> methodKeys = new LinkedHashSet<>();
        Set<String> simpleKeys = new LinkedHashSet<>();
    }

    private Allowed buildAllowed(List<Snippet> group) {
        Allowed a = new Allowed();
        Pattern shortId = Pattern.compile("\\b([a-z]{1,2}[0-9]?)\\b");
        for (Snippet s : group) {
            if (s.classFqn != null) a.classKeys.add(s.classFqn);
            if (s.qualifiedSignature != null) a.methodKeys.add(s.qualifiedSignature);
            if (s.decl != null) {
                var m = shortId.matcher(s.decl);
                while (m.find()) a.simpleKeys.add(m.group(1));
            }
            if (s.code != null) {
                var m = shortId.matcher(s.code);
                while (m.find()) a.simpleKeys.add(m.group(1));
            }
        }
        return a;
    }

    @Override
    public void run() {
        try {
            ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            List<Snippet> snippets = om.readValue(Path.of(snippetsJson).toFile(), new TypeReference<>() {});
            Mapping mapping;

            // 1) 先拿“原始建议结果”
            if ("openai".equalsIgnoreCase(llm.provider)) {
                mapping = suggestWithOpenAI(snippets, llm.model, llm.batch);
            } else if ("deepseek".equalsIgnoreCase(llm.provider)) {
                mapping = suggestWithDeepSeek(snippets, llm.model, llm.batch);
            } else if ("local".equalsIgnoreCase(llm.provider)) {
                mapping = suggestWithLocal(snippets, llm.localApi, llm.endpoint, llm.model, llm.batch);
            } else {
                mapping = suggestHeuristically(snippets);
            }

            // 2) 清洗到“允许的键集合”之内
            Allowed allow = buildAllowed(snippets);
            mapping = sanitizeAndFill(mapping, allow, snippets);

            // 3) 统一写盘（不中断，即使部分批次失败，也会写出已有结果）
            om.writeValue(Path.of(mappingJson).toFile(), mapping);
            System.out.println("[suggest] mapping -> " + mappingJson);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    /* ================== dummy heuristic ================== */
    private Mapping suggestHeuristically(List<Snippet> snippets) {
        Mapping m = new Mapping();
        Pattern shortId = Pattern.compile("^[a-z]{1,2}[0-9]?$");
        Map<String, Integer> cnt = new HashMap<>();
        for (Snippet s : snippets) {
            String decl = s.decl == null ? "" : s.decl;
            Arrays.stream(decl.split("[^A-Za-z0-9_]"))
                    .filter(x -> x != null && !x.isBlank())
                    .forEach(id -> { if (shortId.matcher(id).matches()) cnt.merge(id, 1, Integer::sum); });
        }
        String[] pool = {"value","tmp","i","j","k","idx","count","flag","size","buf","item","data","map","list","user","token"};
        int i = 0;
        for (var e : cnt.entrySet().stream()
                .sorted((a,b)->Integer.compare(b.getValue(), a.getValue()))
                .limit(32).collect(Collectors.toList())) {
            String old = e.getKey();
            String nn = pool[i % pool.length] + (i/pool.length==0?"":(i/pool.length));
            if (!old.equals(nn)) m.simple.put(old, nn);
            i++;
        }
        for (Snippet s : snippets) {
            if (s.methodName != null && s.methodName.length() <= 2 && (s.paramTypes == null || s.paramTypes.isEmpty())) {
                String newName = "get" + (s.className == null ? "Value" : s.className);
                if (s.qualifiedSignature != null) m.methodSig.putIfAbsent(s.qualifiedSignature, newName);
            }
        }
        System.out.println("[suggest] dummy: simple=" + m.simple.size() + ", methodSig=" + m.methodSig.size());
        return m;
    }

    /* ================== OpenAI provider ================== */
    private Mapping suggestWithOpenAI(List<Snippet> snippets, String model, int batchSize) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[suggest/openai] OPENAI_API_KEY missing. Fallback to dummy.");
            return suggestHeuristically(snippets);
        }

        List<String> blocks = snippets.stream()
                .map(s -> formatSnippetBlockTruncated(s, codeLines, codeLines))
                .toList();

        com.initialone.jhumanify.llm.LlmClient client =
                new com.initialone.jhumanify.llm.OpenAiClient(apiKey, model, batchSize); // ← 修复：使用 OpenAiClient

        List<RenameSuggestion> items =
                callInBatches(client, blocks, tokenBudget, llm.batch, llm.maxConcurrent);

        Mapping raw = suggestionsToMapping(items, snippets);
        Allowed allow = buildAllowed(snippets);
        return sanitizeAndFill(raw, allow, snippets);
    }

    /* ================== DeepSeek provider ================== */
    private Mapping suggestWithDeepSeek(List<Snippet> snippets,
                                        String model,
                                        int batchSize) throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[suggest/deepseek] DEEPSEEK_API_KEY missing. Fallback to dummy.");
            return suggestHeuristically(snippets);
        }

        com.initialone.jhumanify.llm.LlmClient client =
                new com.initialone.jhumanify.llm.DeepSeekClient(apiKey, model, batchSize, llm.timeoutSec);

        List<String> blocks = snippets.stream()
                .map(s -> formatSnippetBlockTruncated(s, codeLines, codeLines))
                .toList();

        List<RenameSuggestion> items =
                callInBatches(client, blocks, tokenBudget, llm.batch, llm.maxConcurrent);

        return suggestionsToMapping(items, snippets);
    }

    /* ================== Local provider (openai-compat / ollama) ================== */
    private Mapping suggestWithLocal(List<Snippet> snippets,
                                     String localApi,
                                     String endpoint,
                                     String model,
                                     int batchSize) throws Exception {
        com.initialone.jhumanify.llm.LlmClient client =
                new com.initialone.jhumanify.llm.LocalClient(localApi, endpoint, model, batchSize, llm.timeoutSec);

        List<String> blocks = snippets.stream()
                .map(s -> formatSnippetBlockTruncated(s, codeLines, codeLines))
                .toList();

        List<RenameSuggestion> items =
                callInBatches(client, blocks, tokenBudget, llm.batch, llm.maxConcurrent);

        return suggestionsToMapping(items, snippets);
    }

    private String formatSnippetBlock(Snippet s) {
        StringBuilder sb = new StringBuilder();
        sb.append("// META\n");
        sb.append("packageName: ").append(nvl(s.pkg)).append("\n");
        sb.append("className: ").append(nvl(s.className)).append("\n");
        sb.append("classFqn: ").append(nvl(s.classFqn)).append("\n");
        sb.append("methodName: ").append(nvl(s.methodName)).append("\n");
        sb.append("methodSignature: ").append(nvl(s.qualifiedSignature)).append("\n");
        if (s.paramTypes != null && !s.paramTypes.isEmpty()) {
            sb.append("paramTypes: ").append(String.join(",", s.paramTypes)).append("\n");
        }
        if (s.strings != null && !s.strings.isEmpty()) {
            sb.append("strings: ").append(String.join(" | ", s.strings.stream().limit(8).toList())).append("\n");
        }
        sb.append("\n");
        sb.append("```java\n")
                .append(s.code == null ? "" : s.code)
                .append("\n```\n");
        return sb.toString();
    }

    // 只保留头/尾若干行，显著降低 token
    private String formatSnippetBlockTruncated(Snippet s, int head, int tail) {
        String code = s.code == null ? "" : s.code;
        String trimmed = trimCode(code, head, tail);
        Snippet copy = new Snippet();
        copy.pkg = s.pkg; copy.className = s.className; copy.classFqn = s.classFqn;
        copy.methodName = s.methodName; copy.qualifiedSignature = s.qualifiedSignature;
        copy.paramTypes = s.paramTypes; copy.strings = s.strings; copy.code = trimmed;
        return formatSnippetBlock(copy);
    }

    private static String trimCode(String code, int head, int tail) {
        String[] lines = code.split("\\R", -1);
        if (lines.length <= head + tail + 5) return code; // 小文件不截
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(head, lines.length); i++) sb.append(lines[i]).append('\n');
        sb.append("// ... (trimmed) ...\n");
        for (int i = Math.max(lines.length - tail, 0); i < lines.length; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    private Mapping suggestionsToMapping(List<RenameSuggestion> items, List<Snippet> context) {
        Mapping m = new Mapping();
        if (items == null) return m;
        Map<String, List<Snippet>> byClass = context.stream().collect(Collectors.groupingBy(s -> s.className));
        Map<String, List<Snippet>> byMethod = context.stream().collect(Collectors.groupingBy(s -> s.methodName));
        for (RenameSuggestion r : items) {
            if (r == null || r.newName == null || r.newName.isBlank() || r.oldName == null) continue;
            switch (r.kind) {
                case "class" -> {
                    var list = byClass.getOrDefault(r.oldName, List.of());
                    for (Snippet s : list) {
                        if (s.classFqn == null) continue;
                        String oldFqn = s.classFqn;
                        String pkg = oldFqn.contains(".") ? oldFqn.substring(0, oldFqn.lastIndexOf('.')) : "";
                        String newFqn = pkg.isEmpty() ? r.newName : (pkg + "." + r.newName);
                        m.classFqn.putIfAbsent(oldFqn, newFqn);
                    }
                }
                case "method" -> {
                    var list = byMethod.getOrDefault(r.oldName, List.of());
                    for (Snippet s : list) {
                        if (s.qualifiedSignature != null)
                            m.methodSig.putIfAbsent(s.qualifiedSignature, r.newName);
                    }
                }
                case "field" -> m.simple.putIfAbsent(r.oldName, r.newName);
                case "var"   -> m.simple.putIfAbsent(r.oldName, r.newName);
                default -> {}
            }
        }
        return m;
    }

    /* 清洗 + 兜底：过滤无效键，保证最少可用输出 */
    private Mapping sanitizeAndFill(Mapping in, Allowed allow, List<Snippet> group) {
        Mapping out = new Mapping();
        if (in == null) in = new Mapping();
        in.classFqn.forEach((k,v)->{ if (allow.classKeys.contains(k) && v!=null && !v.isBlank()) out.classFqn.put(k, v); });
        in.methodSig.forEach((k,v)->{ if (allow.methodKeys.contains(k) && v!=null && !v.isBlank()) out.methodSig.put(k, v); });
        if (in.fieldFqn != null) {
            in.fieldFqn.forEach((k,v)->{
                if (k!=null && k.contains("#") && v!=null && !v.isBlank()) out.fieldFqn.put(k, v);
            });
        }
        in.simple.forEach((k,v)->{
            if (allow.simpleKeys.contains(k) && v!=null && !v.isBlank() && !v.equals(k)) out.simple.put(k, v);
        });

        // 兜底：没有方法建议就猜一个最小可用的
        if (out.methodSig.isEmpty()) {
            for (Snippet s : group) {
                if (s.qualifiedSignature == null) continue;
                String code = s.code == null ? "" : s.code;
                String guess = code.contains("+") ? "add" :
                        (code.contains("get") ? "get" :
                                (code.contains("put") ? "put" : "process"));
                out.methodSig.putIfAbsent(s.qualifiedSignature, guess);
            }
        }
        // 兜底：simple 至少给几个
        if (out.simple.isEmpty()) {
            String[] pool = {"value","sum","count","idx","base","data","item"};
            int i = 0;
            for (String k : allow.simpleKeys) {
                if (i >= pool.length) break;
                if (!k.equals(pool[i])) out.simple.put(k, pool[i]);
                i++;
            }
        }
        return out;
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    /* ======== 批量并发 + “不崩溃”重试 ======== */

    private static int approxTokens(String s) {
        // 粗估：1 token ≈ 3.5 字符（足够做分批）
        return (int) Math.ceil((s == null ? 0 : s.length()) / 3.5);
    }

    private static <T> List<List<T>> batchByBudget(
            List<T> items, ToIntFunction<T> tokenizer, int tokenBudgetPerBatch, int maxItemsPerBatch) {
        List<List<T>> batches = new ArrayList<>();
        List<T> cur = new ArrayList<>();
        int curTok = 0;
        for (T it : items) {
            int t = Math.max(1, tokenizer.applyAsInt(it));
            boolean overflow = (!cur.isEmpty()) &&
                    (curTok + t > tokenBudgetPerBatch || cur.size() >= maxItemsPerBatch);
            if (overflow) { batches.add(cur); cur = new ArrayList<>(); curTok = 0; }
            cur.add(it); curTok += t;
            if (curTok > tokenBudgetPerBatch) { batches.add(cur); cur = new ArrayList<>(); curTok = 0; }
        }
        if (!cur.isEmpty()) batches.add(cur);
        return batches;
    }

    // —— 核心：任何一批失败都不会抛异常，只记录错误并返回空列表，流水线继续 ——
    private List<RenameSuggestion> callInBatches(
            com.initialone.jhumanify.llm.LlmClient client,
            List<String> blocks,
            int tokenBudget, int maxItemsPerBatch, int maxConcurrent
    ) throws Exception {
        List<List<String>> batches = batchByBudget(blocks, SuggestCmd::approxTokens, tokenBudget, maxItemsPerBatch);
        System.out.println("[suggest] total blocks=" + blocks.size() + ", batches=" + batches.size());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, maxConcurrent));
        try {
            List<Future<List<RenameSuggestion>>> futures = new ArrayList<>();
            AtomicInteger idx = new AtomicInteger(0);
            for (List<String> b : batches) {
                futures.add(pool.submit(() -> {
                    int i = idx.incrementAndGet();
                    String tag = "LLM batch " + i + "/" + batches.size() + " items=" + b.size();
                    System.out.println("[suggest] " + tag);
                    // 不抛异常的重试：失败返回 null/empty
                    List<RenameSuggestion> part = withRetrySilently(retries, retryInitialMs, retryMultiplier, retryJitterMs,
                            () -> client.suggestRenames(b), tag);
                    if (part == null) return Collections.emptyList();
                    return part;
                }));
            }
            List<RenameSuggestion> all = new ArrayList<>();
            for (Future<List<RenameSuggestion>> f : futures) {
                try {
                    List<RenameSuggestion> part = f.get();
                    if (part != null) all.addAll(part);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ee) {
                    // 理论上不会到这里（任务内部吞了异常），保险起见仍然不中断
                    System.err.println("[suggest] batch future failed: " + ee.getCause());
                }
            }
            return all;
        } finally {
            pool.shutdown();
        }
    }

    // 兼容某些 client 返回自定义类型时的批处理（同样不抛异常）
    private <T> List<T> callInBatchesRaw(
            com.initialone.jhumanify.llm.LlmClient client,
            List<String> blocks,
            int tokenBudget, int maxItemsPerBatch, int maxConcurrent
    ) throws Exception {
        List<List<String>> batches = batchByBudget(blocks, SuggestCmd::approxTokens, tokenBudget, maxItemsPerBatch);
        System.out.println("[suggest] total blocks=" + blocks.size() + ", batches=" + batches.size());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, maxConcurrent));
        try {
            List<Future<List<T>>> futures = new ArrayList<>();
            AtomicInteger idx = new AtomicInteger(0);
            for (List<String> b : batches) {
                futures.add(pool.submit(() -> {
                    int i = idx.incrementAndGet();
                    String tag = "LLM batch " + i + "/" + batches.size() + " items=" + b.size();
                    System.out.println("[suggest] " + tag);
                    @SuppressWarnings("unchecked")
                    List<T> part = withRetrySilently(retries, retryInitialMs, retryMultiplier, retryJitterMs,
                            () -> (List<T>) client.suggestRenames(b), tag);
                    if (part == null) return Collections.emptyList();
                    return part;
                }));
            }
            List<T> all = new ArrayList<>();
            for (Future<List<T>> f : futures) {
                try {
                    List<T> part = f.get();
                    if (part != null) all.addAll(part);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ee) {
                    System.err.println("[suggest] batch future failed: " + ee.getCause());
                }
            }
            return all;
        } finally {
            pool.shutdown();
        }
    }

    /* ============ 重试封装（不抛异常版） ============ */
    private static <T> T withRetrySilently(int maxAttempts,
                                           long initialBackoffMs,
                                           double multiplier,
                                           long jitterMs,
                                           Callable<T> op,
                                           String tag) {
        long backoff = initialBackoffMs;
        Throwable last = null;
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                return op.call();
            } catch (Throwable t) {
                last = t;
                boolean retryable = isTransient(t);
                if (!retryable) {
                    System.err.printf("[suggest] %s non-retryable error: %s%n", tag, t.toString());
                    return null;
                }
                long jitter = 0;
                try {
                    jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(0, jitterMs) + 1);
                } catch (Throwable ignore) {}
                long sleep = backoff + jitter;
                System.err.printf("[suggest] %s attempt %d/%d failed: %s; backoff %dms%n",
                        tag, attempt, maxAttempts, t.toString(), sleep);
                try { Thread.sleep(sleep); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                backoff = (long) Math.min(30_000, backoff * Math.max(1.0, multiplier));
            }
        }
        System.err.printf("[suggest] %s giving up after %d attempts: %s%n", tag, maxAttempts, last == null ? "unknown" : last.toString());
        return null;
    }

    private static boolean isTransient(Throwable t) {
        String s = String.valueOf(t).toLowerCase();
        return (t instanceof java.io.InterruptedIOException)           // 超时/中断
                || (t instanceof java.net.SocketTimeoutException)
                || (t instanceof java.net.ProtocolException)
                || s.contains("stream was reset")
                || s.contains("cancel") || s.contains("canceled")
                || s.contains("timeout")
                || s.contains("connection reset")
                || s.contains("refused")
                || s.contains("unavailable")
                || s.contains("temporary");
    }
}