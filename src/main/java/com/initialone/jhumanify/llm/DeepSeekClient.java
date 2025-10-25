package com.initialone.jhumanify.llm;

import com.initialone.jhumanify.model.RenameSuggestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * DeepSeek Chat Completions 客户端（OpenAI 兼容）。
 *
 * 约定：LLM 返回 JSON 形如：
 * {
 *   "renames":[
 *     {"kind":"class","old":"Foo","new":"Adder"},
 *     {"kind":"method","old":"b","new":"add"},
 *     {"kind":"field","old":"a","new":"base"},
 *     {"kind":"var","old":"x","new":"value"}
 *   ]
 * }
 *
 * 也兼容直接返回数组：
 * [
 *   {"kind":"class","old":"Foo","new":"Adder"},
 *   ...
 * ]
 *
 * 重要修改：
 * - 不再手工拼接 JSON 字符串，而是用 ObjectMapper 序列化，避免非法字符导致 400。
 * - 在发给 DeepSeek 前会对 prompt 做 sanitize，过滤掉 0x00~0x1F 中除了 \n\r\t 的控制字符。
 */
public class DeepSeekClient implements LlmClient {

    private static final MediaType MEDIA_JSON = MediaType.parse("application/json");

    private final OkHttpClient http;
    private final ObjectMapper om = new ObjectMapper(); // 用于解析 DeepSeek 回复 & 构建请求

    private final String apiKey;
    private final String model;
    private final String baseUrl;     // e.g. https://api.deepseek.com 或 https://api.deepseek.com/v1
    private final int batchSize;

    public DeepSeekClient(String apiKey, String model, int batchSize) {
        this(apiKey, model, "https://api.deepseek.com", batchSize, 180);
    }

    public DeepSeekClient(String apiKey, String model, String baseUrl, int batchSize, int timeoutSec) {
        this.apiKey = apiKey;
        this.model = model;

        // 统一补上 /v1
        String b = (baseUrl == null || baseUrl.isBlank()) ? "https://api.deepseek.com" : baseUrl;
        b = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        if (!b.endsWith("/v1")) b = b + "/v1";
        this.baseUrl = b;

        this.batchSize = Math.max(1, batchSize);

        this.http = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(Duration.ofSeconds(20))
                .readTimeout(Duration.ofSeconds(timeoutSec))
                .writeTimeout(Duration.ofSeconds(timeoutSec))
                .callTimeout(Duration.ofSeconds(timeoutSec + 30))
                // 优先 HTTP/2（避免某些 chunked 边缘问题），回退到 HTTP/1.1
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build();
    }

    @Override
    public List<RenameSuggestion> suggestRenames(List<String> snippetBlocks) throws Exception {
        List<RenameSuggestion> out = new ArrayList<>();
        for (int i = 0; i < snippetBlocks.size(); i += batchSize) {
            int to = Math.min(i + batchSize, snippetBlocks.size());
            List<String> group = snippetBlocks.subList(i, to);

            // 构造大 prompt
            String prompt = buildPrompt(group);

            // 调 DeepSeek
            String body = callDeepSeek(prompt);

            // 提取 DeepSeek 返回的 message.content
            String content = extractMessageContent(body);

            // 再从 content 中解析我们约定的 JSON（renames 列表）
            JsonNode root = tryParseJson(content);
            if (root == null) continue;

            if (root.isObject() && root.get("renames") != null && root.get("renames").isArray()) {
                appendRenames(out, root.get("renames"));
            } else if (root.isArray()) {
                appendRenames(out, root);
            } else {
                // ignore silently
            }
        }
        return out;
    }

    /* =========================================================
     *                     HTTP 调用部分
     * ========================================================= */

    private String callDeepSeek(String prompt) throws Exception {
        String url = baseUrl + "/chat/completions";

        // 过滤控制字符，避免 DeepSeek 直接报 400
        String safeUserContent = sanitizeForJson(prompt);

        // 我们构造一个符合 OpenAI Chat Completions 风格的请求体
        ChatRequest reqPayload = new ChatRequest();
        reqPayload.model = model;
        reqPayload.temperature = 0.2;
        reqPayload.stream = false;
        reqPayload.messages = List.of(
                new ChatMessage(
                        "system",
                        "You are an expert Java deobfuscation assistant. " +
                                "Return ONLY JSON as specified."
                ),
                new ChatMessage(
                        "user",
                        safeUserContent
                )
        );

        // 用 Jackson 序列化成合法 JSON
        String payloadJson = om.writeValueAsString(reqPayload);

        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // 避免 gzip + chunked 的奇怪组合，加上 Connection: close 也能避免复用脏连接
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .post(RequestBody.create(payloadJson, MEDIA_JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                String err = safeReadBody(resp.body());
                throw new IllegalStateException("DeepSeek HTTP " + resp.code() +
                        " TE=" + resp.header("Transfer-Encoding") +
                        " CE=" + resp.header("Content-Encoding") +
                        " body=" + err);
            }
            ResponseBody rb = resp.body();
            if (rb == null) {
                throw new IllegalStateException("Empty body");
            }
            byte[] bytes = rb.bytes(); // 避免 OkHttp 的 string() 做 charset 魔改
            MediaType mt = rb.contentType();
            Charset cs = (mt != null && mt.charset() != null) ? mt.charset() : StandardCharsets.UTF_8;
            return new String(bytes, cs);
        }
    }

    private static String safeReadBody(ResponseBody b) {
        if (b == null) return "";
        try {
            byte[] bytes = b.bytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<" + e.getClass().getSimpleName() + ">";
        }
    }

    /**
     * 把 prompt 里的“非法控制字符”清理掉，避免 JSON 无法解析。
     * - 保留 \n\r\t（这些我们希望留给 LLM，用来保持代码格式）
     * - 其他 <0x20 的字符，用空格代替
     */
    private static String sanitizeForJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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

    /* =========================================================
     *                     Prompt 构造
     * ========================================================= */

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
        for (String s : group) {
            sb.append(s).append("\n\n");
        }
        return sb.toString();
    }

    /* =========================================================
     *                   DeepSeek 回复解析
     * ========================================================= */

    /**
     * DeepSeek 返回的是 OpenAI-style：
     * {
     *   "choices": [
     *     {
     *       "message": {
     *         "role": "...",
     *         "content": "..."   <-- 我们关心这个
     *       }
     *     }
     *   ]
     * }
     */
    private String extractMessageContent(String body) {
        try {
            JsonNode root = om.readTree(body);
            JsonNode node = root.path("choices").path(0).path("message").path("content");
            if (node.isMissingNode() || node.isNull()) return body;
            return node.asText();
        } catch (Exception e) {
            // fallback: 旧的手工提取方式
            int i = body.lastIndexOf("\"content\":\"");
            if (i < 0) return body;
            StringBuilder sb = new StringBuilder();
            boolean esc = false;
            for (int idx = i + 11; idx < body.length(); idx++) {
                char c = body.charAt(idx);
                if (esc) {
                    if (c == 'n') { sb.append('\n'); }
                    else if (c == 't') { sb.append('\t'); }
                    else { sb.append(c); }
                    esc = false;
                    continue;
                }
                if (c == '\\') {
                    esc = true;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                sb.append(c);
            }
            return sb.toString();
        }
    }

    /**
     * 尝试把 LLM 的 content 解析成 JSON：
     * - 去掉 ```json ... ``` 包裹
     * - 找到第一个 { 或 [
     * - 用 Jackson 读成树
     */
    private JsonNode tryParseJson(String content) {
        String cleaned = content;
        int fence = cleaned.indexOf("```");
        if (fence >= 0) {
            int fence2 = cleaned.indexOf("```", fence + 3);
            if (fence2 > fence) {
                cleaned = cleaned.substring(fence + 3, fence2);
            }
        }

        cleaned = cleaned.trim();
        int stObj = cleaned.indexOf('{');
        int stArr = cleaned.indexOf('[');

        try {
            if (stArr >= 0 && (stObj < 0 || stArr < stObj)) {
                // 以数组开头
                return om.readTree(cleaned.substring(stArr));
            } else if (stObj >= 0) {
                return om.readTree(cleaned.substring(stObj));
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把 LLM 返回的 renames 数组转成 RenameSuggestion 列表
     */
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
            if (v != null && v.isValueNode()) {
                return v.asText();
            }
        }
        return null;
    }

    /* =========================================================
     *                    内部小模型类
     * ========================================================= */

    /**
     * Chat Completions 风格的 message
     */
    public static class ChatMessage {
        public String role;
        public String content;
        public ChatMessage() {}
        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /**
     * 发给 DeepSeek 的请求体
     */
    public static class ChatRequest {
        public String model;
        public List<ChatMessage> messages;
        public double temperature;
        public boolean stream;
    }
}