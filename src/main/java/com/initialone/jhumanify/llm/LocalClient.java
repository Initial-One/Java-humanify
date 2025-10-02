package com.initialone.jhumanify.llm;

import com.initialone.jhumanify.model.RenameSuggestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 本地 LLM 客户端：支持 openai 兼容 / ollama。 */
public class LocalClient implements LlmClient {

    private final OkHttpClient http;
    private final ObjectMapper om = new ObjectMapper();

    private final String apiType;     // "openai" | "ollama"
    private final String endpoint;    // e.g. http://localhost:1234/v1  或 http://localhost:11434
    private final String model;
    private final int batchSize;

    public LocalClient(String apiType, String endpoint, String model, int batchSize, int timeoutSec) {
        this.apiType = (apiType == null || apiType.isBlank()) ? "ollama" : apiType.toLowerCase();
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.model = model;
        this.batchSize = Math.max(1, batchSize);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(Math.max(10, Math.min(60, Math.max(1, timeoutSec / 3)))))
                .readTimeout(Duration.ofSeconds(Math.max(10, timeoutSec)))
                .writeTimeout(Duration.ofSeconds(Math.max(10, timeoutSec)))
                .callTimeout(Duration.ofSeconds(Math.max(30, timeoutSec + 30)))
                .build();
    }

    @Override
    public List<RenameSuggestion> suggestRenames(List<String> snippetBlocks) throws Exception {
        List<RenameSuggestion> out = new ArrayList<>();
        for (int i = 0; i < snippetBlocks.size(); i += batchSize) {
            int to = Math.min(i + batchSize, snippetBlocks.size());
            List<String> group = snippetBlocks.subList(i, to);
            String prompt = buildPrompt(group);

            String body = switch (apiType) {
                case "openai" -> callOpenAiCompat(prompt);
                case "ollama" -> callOllama(prompt);
                default -> throw new IllegalArgumentException("Unsupported local api: " + apiType);
            };

            String content = extractMessageContent(body);
            JsonNode root = tryParseJson(content);
            if (root == null) continue;

            if (root.isObject() && root.get("renames") != null && root.get("renames").isArray()) {
                appendRenames(out, root.get("renames"));
            } else if (root.isArray()) {
                appendRenames(out, root);
            }
        }
        return out;
    }

    /* ---------- HTTP ---------- */

    private String callOpenAiCompat(String prompt) throws Exception {
        String url = endpoint + "/chat/completions";
        String payload = """
        {
          "model": %s,
          "messages": [
            {"role":"system","content":"You are an expert Java deobfuscation assistant. Return ONLY JSON as specified."},
            {"role":"user","content": %s}
          ],
          "temperature": 0.2
        }
        """.formatted(jsonQuote(model), jsonQuote(prompt));
        Request req = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload, MediaType.parse("application/json")))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IllegalStateException("local(openai) HTTP " + resp.code() + " " + resp.message());
            ResponseBody rb = resp.body(); if (rb == null) throw new IllegalStateException("Empty body");
            return rb.string();
        }
    }

    private String callOllama(String prompt) throws Exception {
        String url = endpoint + "/api/chat";
        String payload = """
        {
          "model": %s,
          "messages": [
            {"role":"system","content":"You are an expert Java deobfuscation assistant. Return ONLY JSON as specified."},
            {"role":"user","content": %s}
          ],
          "options": {"temperature": 0.2},
          "stream": false
        }
        """.formatted(jsonQuote(model), jsonQuote(prompt));
        Request req = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload, MediaType.parse("application/json")))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IllegalStateException("local(ollama) HTTP " + resp.code() + " " + resp.message());
            ResponseBody rb = resp.body(); if (rb == null) throw new IllegalStateException("Empty body");
            return rb.string();
        }
    }

    private static String jsonQuote(String s) {
        String esc = s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");
        return "\"" + esc + "\"";
    }

    /* ---------- Prompt ---------- */

    private String buildPrompt(List<String> group) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                "Given Java snippets, propose semantic renames as JSON with key `renames`, " +
                        "where `renames` is an array of {kind,old,new}.\n" +
                        "- kind ∈ {\"class\",\"method\",\"field\",\"var\"}.\n" +
                        "- Use **the META header** to determine original names:\n" +
                        "  * For class rename, old MUST equal `className` from META (e.g., `a`).\n" +
                        "  * For method rename, old MUST equal `methodName` from META (e.g., `O0O0`).\n" +
                        "  * For variables/fields, old is the short identifier visible in the code body.\n" +
                        "- Provide meaningful `new` names (CamelCase for classes; lowerCamelCase for methods/vars/fields).\n" +
                        "- If `className` looks obfuscated (length ≤ 2 or matches [a-z]\\d+), STRONGLY propose a class rename for it.\n" +
                        "OUTPUT STRICT JSON like:\n" +
                        "{\"renames\":[{\"kind\":\"class\",\"old\":\"Foo\",\"new\":\"Base64Codec\"}," +
                        "{\"kind\":\"method\",\"old\":\"O0O0\",\"new\":\"encode\"}," +
                        "{\"kind\":\"method\",\"old\":\"o0O0\",\"new\":\"decode\"}," +
                        "{\"kind\":\"var\",\"old\":\"x\",\"new\":\"value\"}]}\n\n"
        );
        sb.append("SNIPPETS:\n");
        for (String s : group) sb.append(s).append("\n\n");
        return sb.toString();
    }

    /* ---------- Parse ---------- */

    private String extractMessageContent(String body) {
        try {
            JsonNode root = om.readTree(body);
            // openai-compat
            JsonNode x = root.path("choices").path(0).path("message").path("content");
            if (!x.isMissingNode() && !x.isNull()) return x.asText();
            // ollama
            x = root.path("message").path("content");
            if (!x.isMissingNode() && !x.isNull()) return x.asText();
            x = root.path("response");
            if (!x.isMissingNode() && !x.isNull()) return x.asText();
            return body;
        } catch (Exception e) {
            // 退化：body 里直接找 "content":"..."
            int i = body.lastIndexOf("\"content\":\"");
            if (i < 0) return body;
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
    }

    private JsonNode tryParseJson(String content) {
        // 去掉 ```json ... ``` 包裹
        String cleaned = content;
        int fence = cleaned.indexOf("```");
        if (fence >= 0) {
            int fence2 = cleaned.indexOf("```", fence + 3);
            if (fence2 > fence) cleaned = cleaned.substring(fence + 3, fence2);
        }
        cleaned = cleaned.trim();
        int stObj = cleaned.indexOf('{');
        int stArr = cleaned.indexOf('[');
        try {
            if (stArr >= 0 && (stObj < 0 || stArr < stObj)) {
                return om.readTree(cleaned.substring(stArr)); // 顶层数组
            } else if (stObj >= 0) {
                return om.readTree(cleaned.substring(stObj)); // 顶层对象
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void appendRenames(List<RenameSuggestion> out, JsonNode arr) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) continue;
            String kind = pickText(n, "kind", "type");
            String oldN = pickText(n, "old", "from", "key", "name");
            String newN = pickText(n, "new", "to", "value", "rename");
            if (kind == null || oldN == null || newN == null) continue;

            RenameSuggestion r = new RenameSuggestion();
            r.kind = kind; r.oldName = oldN; r.newName = newN;
            out.add(r);
        }
    }

    private static String pickText(JsonNode obj, String... keys) {
        for (String k : keys) {
            JsonNode v = obj.get(k);
            if (v != null && v.isValueNode()) return v.asText();
        }
        return null;
    }
}