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
                mapping = suggestWithDeepSeekViaClient(snippets, model, dsBase, dsKey, batch);
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
            System.err.println("[suggest] OPENAI_API_KEY missing. Fallback to dummy.");
            return suggestHeuristically(snippets);
        }
        LlmClient client = new OpenAiClient(apiKey, model, batchSize);
        List<String> blocks = snippets.stream().map(this::formatSnippetBlock).toList();
        List<RenameSuggestion> items = client.suggestRenames(blocks);
        Mapping m0 = suggestionsToMapping(items, snippets);
        return m0;
    }

    /* ================== DeepSeek provider ================== */
    private Mapping suggestWithDeepSeekViaClient(List<Snippet> snippets,
                                                 String model,
                                                 String baseUrl,
                                                 String keyOpt,
                                                 int batchSize) throws Exception {
        String apiKey = (keyOpt != null && !keyOpt.isBlank()) ? keyOpt : System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[suggest/deepseek] DEEPSEEK_API_KEY missing. Fallback to dummy.");
            return suggestHeuristically(snippets);
        }
        LlmClient client = new DeepSeekClient(apiKey, model, baseUrl, batchSize, 180);
        List<String> blocks = snippets.stream().map(this::formatSnippetBlock).toList();
        var items = client.suggestRenames(blocks);
        return suggestionsToMapping(items, snippets);
    }

    /* ================== Local provider (ollama / openai-compat) ================== */
    private Mapping suggestWithLocal(List<Snippet> snippets,
                                     String localApi,
                                     String endpoint,
                                     String model,
                                     int batchSize) throws Exception {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(Math.max(10, Math.min(60, timeoutSec / 3))))
                .readTimeout(Duration.ofSeconds(timeoutSec))
                .writeTimeout(Duration.ofSeconds(timeoutSec))
                .callTimeout(Duration.ofSeconds(timeoutSec + 30))
                .build();

        Mapping merged = new Mapping();
        List<List<Snippet>> batches = chunk(snippets, batchSize);
        int idx = 1;
        for (List<Snippet> group : batches) {
            Allowed allow = buildAllowed(group);
            String prompt = buildPrompt(group, allow);

            String content;
            if ("openai".equalsIgnoreCase(localApi)) {
                String url = joinUrl(endpoint, "/chat/completions");
                String payload = """
                {"model":%s,"messages":[
                  {"role":"system","content":"You are an expert Java deobfuscation assistant. Return ONLY JSON."},
                  {"role":"user","content": %s}
                ],"temperature":0.2}
                """.formatted(jsonQuote(model), jsonQuote(prompt));
                Request req = new Request.Builder()
                        .url(url).header("Content-Type","application/json")
                        .post(RequestBody.create(payload, MediaType.parse("application/json"))).build();
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        System.err.println("[suggest/local-openai] HTTP " + resp.code() + " " + resp.message() + ", batch " + idx);
                        continue;
                    }
                    content = extractContent(Objects.requireNonNull(resp.body()).string());
                }
            } else if ("ollama".equalsIgnoreCase(localApi)) {
                String url = joinUrl(endpoint, "/api/chat");
                String payload = """
                {"model":%s,"messages":[
                  {"role":"system","content":"You are an expert Java deobfuscation assistant. Return ONLY JSON."},
                  {"role":"user","content": %s}
                ],"options":{"temperature":0.2},"stream":false}
                """.formatted(jsonQuote(model), jsonQuote(prompt));
                Request req = new Request.Builder()
                        .url(url).header("Content-Type","application/json")
                        .post(RequestBody.create(payload, MediaType.parse("application/json"))).build();
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        System.err.println("[suggest/local-ollama] HTTP " + resp.code() + " " + resp.message() + ", batch " + idx);
                        continue;
                    }
                    content = extractOllamaContent(Objects.requireNonNull(resp.body()).string());
                }
            } else {
                throw new IllegalArgumentException("Unsupported --local-api: " + localApi);
            }

            Mapping part = tryParseMapping(content);
            // 先对单批做一次清洗（只保留 batch 内允许的键），避免错键污染
            Mapping cleaned = sanitizeAndFill(part, allow, group);
            mergeInto(merged, cleaned);
            System.out.println("[suggest] local batch " + idx + "/" + batches.size() + " merged.");
            idx++;
        }
        return merged;
    }

    /* ================== Prompt / 解析 / 合并 ================== */
    private String buildPrompt(List<Snippet> group, Allowed allow) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior Java reverse-engineering assistant.\n")
                .append("Return STRICT JSON object with keys: simple (map), classFqn (map), fieldFqn (map), methodSig (map).\n")
                .append("Use ONLY the keys listed below. Do NOT invent keys. Values must be valid Java identifiers.\n")
                .append("Definitions:\n")
                .append("- classFqn: \"oldFqn -> newFqn\" e.g. \"a.b.C\":\"a.b.UserService\" (keep package unless needed)\n")
                .append("- fieldFqn: \"a.b.C#field\" -> \"newName\"\n")
                .append("- methodSig: \"a.b.C.m(T1,T2)\" -> \"newName\" (types as in code)\n")
                .append("- simple: short identifier renames for locals/params/private members\n\n");

        sb.append("ALLOWED classFqn keys:\n");
        for (String k : allow.classKeys) sb.append("- ").append(k).append("\n");
        sb.append("\nALLOWED methodSig keys:\n");
        for (String k : allow.methodKeys) sb.append("- ").append(k).append("\n");
        sb.append("\nALLOWED simple keys (locals/params):\n");
        if (allow.simpleKeys.isEmpty()) {
            sb.append("- a\n- b\n- c\n- x\n- i\n");
        } else {
            for (String k : allow.simpleKeys) sb.append("- ").append(k).append("\n");
        }

        sb.append("\nSNIPPETS:\n");
        for (Snippet s : group) {
            sb.append("// ").append(s.qualifiedSignature).append("\n");
            if (s.strings != null && !s.strings.isEmpty()) {
                sb.append("// strings: ").append(String.join(" | ", s.strings.stream().limit(6).toList())).append("\n");
            }
            sb.append("```java\n").append(s.code == null ? "" : s.code).append("\n```\n\n");
        }
        sb.append("Output ONLY the JSON object.\n");
        return sb.toString();
    }

    private static List<List<Snippet>> chunk(List<Snippet> list, int size) {
        List<List<Snippet>> out = new ArrayList<>();
        int step = Math.max(1, size);
        for (int i=0; i<list.size(); i+=step) out.add(list.subList(i, Math.min(i+step, list.size())));
        return out;
    }

    private static String extractContent(String body) {
        int i = body.lastIndexOf("\"content\":\"");
        if (i<0) return body;
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int idx = i + 11; idx < body.length(); idx++) {
            char c = body.charAt(idx);
            if (esc) { sb.append(c == 'n' ? '\n' : (c == 't' ? '\t' : c)); esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') { break; }
            sb.append(c);
        }
        return sb.toString();
    }

    private Mapping tryParseMapping(String content) {
        int st = content.indexOf('{');
        int ed = content.lastIndexOf('}');
        String json = (st>=0 && ed>st) ? content.substring(st, ed+1) : content;
        ObjectMapper om = new ObjectMapper();
        try {
            return om.readValue(json.getBytes(StandardCharsets.UTF_8), Mapping.class);
        } catch (Exception e) {
            System.err.println("[suggest] parse fail -> return empty: " + e.getMessage());
            return new Mapping();
        }
    }

    private void mergeInto(Mapping base, Mapping add) {
        if (add == null) return;
        base.simple.putAll(add.simple);
        base.classFqn.putAll(add.classFqn);
        base.fieldFqn.putAll(add.fieldFqn);
        base.methodSig.putAll(add.methodSig);
    }

    private String formatSnippetBlock(Snippet s) {
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(s.qualifiedSignature).append("\n");
        if (s.strings != null && !s.strings.isEmpty()) {
            sb.append("// strings: ").append(String.join(" | ", s.strings.stream().limit(6).toList())).append("\n");
        }
        sb.append("```java\n").append(s.code == null ? "" : s.code).append("\n```");
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

    /* ========================= 预设后处理：kv ========================= */
    private Mapping postProcessPreset(Mapping in, List<Snippet> all, String preset) {
        final Mapping out = (in != null) ? in : new Mapping();
        if (!"kv".equalsIgnoreCase(preset)) return out;

        Map<String, List<Snippet>> byFqn = all.stream()
                .filter(s -> s.classFqn != null)
                .collect(Collectors.groupingBy(s -> s.classFqn));

        // 1) main(String[]) -> Cli
        byFqn.forEach((fqn, list) -> {
            boolean hasMain = list.stream().anyMatch(s ->
                    "main".equals(s.methodName) &&
                            (
                                    (s.paramTypes != null && s.paramTypes.size() == 1 &&
                                            (s.paramTypes.get(0).contains("String[]") || (s.decl != null && s.decl.contains("String[]"))))
                                            || (s.decl != null && s.decl.contains("main(") && s.decl.contains("String[]"))
                            )
            );
            if (hasMain) putClassRenameIfAbsent(out.classFqn, fqn, simpleToPkg(fqn, "Cli"));
        });

        // 2) KeyValueStore（P/G/K 或有过期判断/CHM痕迹）
        byFqn.forEach((fqn, list) -> {
            boolean hasPGK = list.stream().anyMatch(s -> "P".equals(s.methodName) || "G".equals(s.methodName) || "K".equals(s.methodName));
            boolean hasKVTrace = list.stream().anyMatch(s -> {
                String c = nvl(s.code);
                return c.contains("expirationTime")
                        || c.contains("System.currentTimeMillis()")
                        || c.contains("ConcurrentHashMap<")
                        || (c.contains(".put(") && c.contains(".get("));
            });
            if (hasPGK || hasKVTrace) {
                putClassRenameIfAbsent(out.classFqn, fqn, simpleToPkg(fqn, "KeyValueStore"));
                list.forEach(s -> {
                    if (s.qualifiedSignature == null) return;
                    switch (s.methodName) {
                        case "P" -> out.methodSig.putIfAbsent(s.qualifiedSignature, "put");
                        case "G" -> out.methodSig.putIfAbsent(s.qualifiedSignature, "get");
                        case "K" -> out.methodSig.putIfAbsent(s.qualifiedSignature, "listKeys");
                        default -> {}
                    }
                });
            }
        });

        // 3) e/d(String) -> encodeKey / decodeKey
        byFqn.forEach((fqn, list) -> list.forEach(s -> {
            if (s.qualifiedSignature == null || s.methodName == null) return;
            List<String> pt = (s.paramTypes == null) ? List.of() : s.paramTypes;
            boolean oneStr = pt.size() == 1 && pt.get(0).contains("String");
            boolean declStr = (s.decl != null && s.decl.startsWith("String "));
            boolean staticLike = (s.decl != null && s.decl.contains("static "));
            if (oneStr && declStr && staticLike) {
                if ("e".equals(s.methodName)) {
                    out.methodSig.putIfAbsent(s.qualifiedSignature, "encodeKey");
                } else if ("d".equals(s.methodName)) {
                    out.methodSig.putIfAbsent(s.qualifiedSignature, "decodeKey");
                }
            }
        }));

        // 4) v0 -> Entry（根据 .value/.expirationTime 的使用痕迹推断包名）
        Set<String> pkgs = all.stream()
                .filter(s -> {
                    String c = nvl(s.code);
                    return c.matches("(?s).*\\bv0\\b.*") && (c.contains(".value") || c.contains(".expirationTime"));
                })
                .map(s -> nvl(s.pkg))
                .filter(p -> !p.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String pkg : pkgs) {
            out.classFqn.putIfAbsent(pkg + ".v0", pkg + ".Entry");
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

    /* ============ Local HTTP helpers ============ */
    private static String joinUrl(String base, String path) {
        if (base.endsWith("/")) base = base.substring(0, base.length()-1);
        return base + path;
    }
    private static String jsonQuote(String s) {
        String esc = s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");
        return "\"" + esc + "\"";
    }
    /** 提取 Ollama chat 的 content（优先 message.content；备选 response） */
    private static String extractOllamaContent(String body) {
        try {
            var om = new ObjectMapper();
            var root = om.readTree(body);
            var msg = root.path("message").path("content").asText(null);
            if (msg != null) return msg;
            var resp = root.path("response").asText(null);
            if (resp != null) return resp;
            return body;
        } catch (Exception e) {
            return body;
        }
    }
}