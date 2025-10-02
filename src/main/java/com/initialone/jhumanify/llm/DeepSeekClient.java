package com.initialone.jhumanify.llm;

import com.initialone.jhumanify.model.RenameSuggestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek Chat Completions 客户端（OpenAI 兼容）。
 * 约定：返回 JSON 形如：
 * {"renames":[{"kind":"class","old":"Foo","new":"Adder"}, {"kind":"method","old":"b","new":"add"}, {"kind":"field","old":"a","new":"base"}, {"kind":"var","old":"x","new":"value"}]}
 * 也兼容：直接返回数组 [{...},{...}]。
 */
public class DeepSeekClient implements LlmClient {

    private final OkHttpClient http;
    private final ObjectMapper om = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final String baseUrl;     // e.g. https://api.deepseek.com
    private final int batchSize;

    public DeepSeekClient(String apiKey, String model, int batchSize) {
        this(apiKey, model, "https://api.deepseek.com", batchSize, 180);
    }

    public DeepSeekClient(String apiKey, String model, String baseUrl, int batchSize, int timeoutSec) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.batchSize = Math.max(1, batchSize);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(20))
                .readTimeout(Duration.ofSeconds(timeoutSec))
                .writeTimeout(Duration.ofSeconds(timeoutSec))
                .callTimeout(Duration.ofSeconds(timeoutSec + 30))
                .build();
    }

    @Override
    public List<RenameSuggestion> suggestRenames(List<String> snippetBlocks) throws Exception {
        List<RenameSuggestion> out = new ArrayList<>();
        for (int i = 0; i < snippetBlocks.size(); i += batchSize) {
            int to = Math.min(i + batchSize, snippetBlocks.size());
            List<String> group = snippetBlocks.subList(i, to);
            String prompt = buildPrompt(group);

            String body = callDeepSeek(prompt);
            String content = extractMessageContent(body);

            // 兼容两种顶层：{"renames":[...]} 或 直接 [...]
            JsonNode root = tryParseJson(content);
            if (root == null) continue;

            if (root.isObject() && root.get("renames") != null && root.get("renames").isArray()) {
                appendRenames(out, root.get("renames"));
            } else if (root.isArray()) {
                appendRenames(out, root);
            } else {
                // 万一是 {"simple":{...}, "classFqn":{...}...} 这种，也做一个最小解析（可选）
                // 留空即可，交给上层的兜底逻辑
            }
        }
        return out;
    }

    /* ------------------------ HTTP ------------------------ */

    private String callDeepSeek(String prompt) throws Exception {
        String url = baseUrl + "/chat/completions";
        String payload = """
        {
          "model": %s,
          "messages": [
            {"role":"system","content":"You are an expert Java deobfuscation assistant. Return ONLY JSON as specified."},
            {"role":"user","content": %s}
          ],
          "temperature": 0.2,
          "stream": false,
          "response_format": {"type":"json_object"}
        }
        """.formatted(jsonQuote(model), jsonQuote(prompt));

        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload, MediaType.parse("application/json")))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("DeepSeek HTTP " + resp.code() + " " + resp.message());
            }
            ResponseBody rb = resp.body();
            if (rb == null) throw new IllegalStateException("Empty body");
            return rb.string();
        }
    }

    private static String jsonQuote(String s) {
        String esc = s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");
        return "\"" + esc + "\"";
    }

    /* ------------------------ Prompt ------------------------ */

    private String buildPrompt(List<String> group) {
        StringBuilder sb = new StringBuilder();
        sb.append("Given Java snippets, propose semantic renames as a JSON object with key `renames`, ")
                .append("where `renames` is an array of {kind, old, new}. ")
                .append("`kind` ∈ {\"class\",\"method\",\"field\",\"var\"}. ")
                .append("Use *simple* names for `old` (as visible in the snippet), ")
                .append("and provide meaningful `new` names (e.g., Adder/add/base/value/sum for adders). ")
                .append("OUTPUT STRICTLY:\n")
                .append("{\"renames\":[{\"kind\":\"class\",\"old\":\"Foo\",\"new\":\"Adder\"},")
                .append("{\"kind\":\"method\",\"old\":\"b\",\"new\":\"add\"},")
                .append("{\"kind\":\"field\",\"old\":\"a\",\"new\":\"base\"},")
                .append("{\"kind\":\"var\",\"old\":\"x\",\"new\":\"value\"},")
                .append("{\"kind\":\"var\",\"old\":\"c\",\"new\":\"sum\"}]} \n\n");

        sb.append("SNIPPETS:\n");
        for (String s : group) {
            sb.append(s).append("\n\n");
        }
        return sb.toString();
    }

    /* ------------------------ Parse ------------------------ */

    private String extractMessageContent(String body) {
        try {
            JsonNode root = om.readTree(body);
            JsonNode node = root.path("choices").path(0).path("message").path("content");
            if (node.isMissingNode() || node.isNull()) return body;
            return node.asText();
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
        // 截取最外层 {}
        int st = cleaned.indexOf('{');
        int ed = cleaned.lastIndexOf('}');
        String json = (st >= 0 && ed > st) ? cleaned.substring(st, ed + 1) : cleaned.trim();
        try {
            // 如果是数组开头
            if (json.startsWith("[")) return om.readTree(json);
            return om.readTree(json);
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
            r.kind = kind;     // "class" | "method" | "field" | "var"
            r.oldName = oldN;
            r.newName = newN;
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