package com.initialone.jhumanify.commands;

import com.initialone.jhumanify.llm.DeepSeekClient;
import com.initialone.jhumanify.llm.LlmClient;
import com.initialone.jhumanify.llm.OpenAiClient;
import com.initialone.jhumanify.model.RenameSuggestion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import okhttp3.*;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@CommandLine.Command(
        name = "suggest",
        description = "Generate rename mapping from snippets.json via OpenAI or local heuristic"
)
public class SuggestCmd implements Runnable {

    @CommandLine.Parameters(index = "0", description = "Input snippets.json (from analyze)")
    String snippetsJson;

    @CommandLine.Parameters(index = "1", description = "Output mapping.json")
    String mappingJson;

    @CommandLine.Option(names = "--provider", defaultValue = "dummy",
            description = "dummy|openai|local|deepseek")
    String provider;

    @CommandLine.Option(names = "--model", defaultValue = "gpt-4o-mini",
            description = "Model name (OpenAI/local/deepseek)")
    String model;

    @CommandLine.Option(names = "--batch", defaultValue = "12",
            description = "Max snippets per LLM batch")
    int batch;

    @CommandLine.Option(names = "--local-api", defaultValue = "ollama",
            description = "When --provider=local: openai|ollama")
    String localApi;

    @CommandLine.Option(names = "--endpoint", defaultValue = "http://localhost:11434",
            description = "Local endpoint. OpenAI-compat: http://localhost:1234/v1; Ollama: http://localhost:11434")
    String endpoint;

    @CommandLine.Option(names = "--timeout-sec", defaultValue = "180",
            description = "HTTP read/call timeout seconds for local provider")
    int timeoutSec;

    @CommandLine.Option(names = "--ds-base", defaultValue = "https://api.deepseek.com",
            description = "DeepSeek API base url")
    String dsBase;

    @CommandLine.Option(names = "--ds-key", description = "DeepSeek API key (fallback to env DEEPSEEK_API_KEY)")
    String dsKey;

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
        public Map<String,String> simple   = new LinkedHashMap<>();
        public Map<String,String> classFqn = new LinkedHashMap<>();
        public Map<String,String> fieldFqn = new LinkedHashMap<>();
        public Map<String,String> methodSig= new LinkedHashMap<>();
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
            if ("openai".equalsIgnoreCase(provider)) {
                mapping = suggestWithOpenAI(snippets, model, batch);
            } else if ("deepseek".equalsIgnoreCase(provider)) {
                mapping = suggestWithDeepSeek(snippets, model, dsBase, dsKey, batch);
            } else if ("local".equalsIgnoreCase(provider)) {
                mapping = suggestWithLocal(snippets, localApi, endpoint, model, batch);
            } else {
                mapping = suggestHeuristically(snippets);
            }

            // 2) 清洗到“允许的键集合”之内
            Allowed allow = buildAllowed(snippets);
            mapping = sanitizeAndFill(mapping, allow, snippets);

            // 3) 统一写盘（只写一次）
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

        // 1) 准备带 META 的片段
        List<String> blocks = snippets.stream()
                .map(this::formatSnippetBlock) // 已包含 META 头 + 代码块
                .toList();

        // 2) 走统一的 LLM 客户端（支持分批）
        com.initialone.jhumanify.llm.LlmClient client =
                new com.initialone.jhumanify.llm.OpenAiClient(apiKey, model, batchSize);

        List<RenameSuggestion> items = client.suggestRenames(blocks);

        // 3) 结构化 -> Mapping
        Mapping raw = suggestionsToMapping(items, snippets);

        // 4) 键上锁 + 清洗兜底（非常关键，过滤掉模型可能多产出的无关键）
        Allowed allow = buildAllowed(snippets);
        Mapping cleaned = sanitizeAndFill(raw, allow, snippets);

        return cleaned;
    }

    /* ================== DeepSeek provider ================== */
    private Mapping suggestWithDeepSeek(List<Snippet> snippets,
                                        String model,
                                        String baseUrl,
                                        String keyOpt,
                                        int batchSize) throws Exception {
        String apiKey = (keyOpt != null && !keyOpt.isBlank())
                ? keyOpt
                : System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[suggest/deepseek] DEEPSEEK_API_KEY missing. Fallback to dummy.");
            return suggestHeuristically(snippets);
        }

        // 注意：DeepSeekClient 在 com.initialone.jhumanify.llm 包
        com.initialone.jhumanify.llm.LlmClient client =
                new com.initialone.jhumanify.llm.DeepSeekClient(apiKey, model, baseUrl, batchSize, 180);

        // 和 OpenAI 分支一致：准备 blocks -> 调 client -> 列表转 Mapping
        List<String> blocks = snippets.stream().map(this::formatSnippetBlock).toList();
        List<com.initialone.jhumanify.model.RenameSuggestion> items = client.suggestRenames(blocks);

        // 如果你沿用原来的 RenameSuggestion（com.example 包），把 import/包名对齐即可。
        // 下面这行是你已有的方法：用 {class,method,field,var} 建议回填到精确键
        Mapping m0 = suggestionsToMapping(
                // 若包名不同，做一次转换或直接统一到你当前工程用的 RenameSuggestion 类
                items.stream().map(r -> {
                    RenameSuggestion x = new RenameSuggestion();
                    x.kind = r.kind; x.oldName = r.oldName; x.newName = r.newName;
                    return x;
                }).toList(),
                snippets
        );
        return m0;
    }

    /* ================== Local provider (openai-compat / ollama) ================== */
    private Mapping suggestWithLocal(List<Snippet> snippets,
                                     String localApi,
                                     String endpoint,
                                     String model,
                                     int batchSize) throws Exception {
        // 新的 LocalClient：支持 openai-compat / ollama
        com.initialone.jhumanify.llm.LlmClient client =
                new com.initialone.jhumanify.llm.LocalClient(localApi, endpoint, model, batchSize, timeoutSec);

        // 和 OpenAI/DeepSeek 分支相同：准备 blocks → 要求重命名 → 列表转 Mapping
        List<String> blocks = snippets.stream().map(this::formatSnippetBlock).toList();
        List<com.initialone.jhumanify.model.RenameSuggestion> items = client.suggestRenames(blocks);

        // 如果你的 RenameSuggestion 仍在 com.example 包，做一次简单转换即可：
        List<RenameSuggestion> items2 = items.stream().map(r -> {
            RenameSuggestion z = new RenameSuggestion();
            z.kind = r.kind; z.oldName = r.oldName; z.newName = r.newName;
            return z;
        }).toList();

        Mapping m0 = suggestionsToMapping(items2, snippets);
        return m0;
    }

    private String formatSnippetBlock(Snippet s) {
        StringBuilder sb = new StringBuilder();

        // === 清晰的结构化头（LLM 主要依据这里提取 old 名称）===
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

        // === 代码体 ===
        sb.append("```java\n")
                .append(s.code == null ? "" : s.code)
                .append("\n```\n");

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
    private static void putClassRenameIfAbsent(Map<String,String> map, String oldFqn, String newFqn) {
        if (oldFqn == null || newFqn == null || oldFqn.equals(newFqn)) return;
        map.putIfAbsent(oldFqn, newFqn);
    }
    private static String simpleToPkg(String fqn, String newSimple) {
        if (fqn == null || fqn.isBlank()) return newSimple;
        int i = fqn.lastIndexOf('.');
        return i < 0 ? newSimple : fqn.substring(0, i) + "." + newSimple;
    }
}