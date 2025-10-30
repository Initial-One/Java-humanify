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
 * 改进点：
 * - 使用 Jackson 序列化请求体，避免非法字符导致 400。
 * - 对 prompt 做控制字符清理，仅保留 \n\r\t。
 * - 内建指数退避重试：对 429 / 5xx / 读写超时 / HTTP2 CANCEL 等做自动重试与退避。
 * - 失败批次不会影响其它批次；由上层进行“批次级不中断”更稳。
 */
public class DeepSeekClient implements LlmClient {

    private static final MediaType MEDIA_JSON = MediaType.parse("application/json");

    private final OkHttpClient http;
    private final ObjectMapper om = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final String baseUrl;   // 规范化到以 /v1 结尾
    private final int batchSize;

    // —— 重试参数（可按需微调，已选取较稳默认值）——
    private final int maxAttempts;           // 每次 HTTP 调用最大尝试次数
    private final long initialBackoffMs;     // 首次重试退避
    private final double backoffMultiplier;  // 指数倍率
    private final long jitterMs;             // 抖动
    private final long rateLimitFloorMs;     // 429 时的下限等待

    public DeepSeekClient(String apiKey, String model, int batchSize) {
        this(apiKey, model, "https://api.deepseek.com", batchSize, 180);
    }

    // 便捷构造：可单独指定超时
    public DeepSeekClient(String apiKey, String model, int batchSize, int timeoutSec) {
        this(apiKey, model, "https://api.deepseek.com", batchSize, timeoutSec);
    }

    public DeepSeekClient(String apiKey, String model, String baseUrl, int batchSize, int timeoutSec) {
        this.apiKey = apiKey;
        this.model = model;

        String b = (baseUrl == null || baseUrl.isBlank()) ? "https://api.deepseek.com" : baseUrl;
        b = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        if (!b.endsWith("/v1")) b = b + "/v1";
        this.baseUrl = b;

        this.batchSize = Math.max(1, batchSize);

        this.http = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(Duration.ofSeconds(20))
                .readTimeout(Duration.ofSeconds(Math.max(1, timeoutSec)))
                .writeTimeout(Duration.ofSeconds(Math.max(1, timeoutSec)))
                .callTimeout(Duration.ofSeconds(Math.max(1, timeoutSec) + 30))
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build();

        // 默认重试参数（和 SuggestCmd 的重试策略相呼应；双层重试更稳）
        this.maxAttempts       = 6;
        this.initialBackoffMs  = 800;
        this.backoffMultiplier = 1.8;
        this.jitterMs          = 300;
        this.rateLimitFloorMs  = 1500;
    }

    @Override
    public List<RenameSuggestion> suggestRenames(List<String> snippetBlocks) throws Exception {
        List<RenameSuggestion> out = new ArrayList<>();
        for (int i = 0; i < snippetBlocks.size(); i += batchSize) {
            int to = Math.min(i + batchSize, snippetBlocks.size());
            List<String> group = snippetBlocks.subList(i, to);

            // 构造 prompt
            String prompt = buildPrompt(group);

            // 带指数退避的 HTTP 调用
            String body = callDeepSeekWithRetry(prompt);

            // 提取 message.content
            String content = extractMessageContent(body);

            // 解析 JSON（支持 {renames:[...]} 或直接数组）
            JsonNode root = tryParseJson(content);
            if (root == null) continue;

            if (root.isObject() && root.get("renames") != null && root.get("renames").isArray()) {
                appendRenames(out, root.get("renames"));
            } else if (root.isArray()) {
                appendRenames(out, root);
            } else {
                // 忽略无效输出
            }
        }
        return out;
    }

    /* =========================================================
     *                     HTTP 调用 + 重试
     * ========================================================= */

    private String callDeepSeekWithRetry(String prompt) throws Exception {
        Throwable last = null;
        long backoff = initialBackoffMs;

        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                return callDeepSeekOnce(prompt);
            } catch (Throwable t) {
                last = t;
                // HTTP 状态类异常在 callDeepSeekOnce 内以 IllegalStateException 抛出，下面统一判断重试性
                if (!isRetryableThrowable(t)) break;

                long sleep = backoff + randJitter(jitterMs);
                // 如果是 429（见 IllegalStateException message），加一个下限
                if (String.valueOf(t).contains("HTTP 429")) {
                    sleep = Math.max(sleep, rateLimitFloorMs);
                }
                System.err.printf("[deepseek] attempt %d/%d failed: %s; backoff %dms%n",
                        attempt, maxAttempts, t.toString(), sleep);
                try { Thread.sleep(sleep); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = (long) Math.min(30_000, backoff * Math.max(1.0, backoffMultiplier));
            }
        }
        // 穷尽重试仍失败，抛给上层（SuggestCmd 已做批次级吞错，不会让总流程崩）
        if (last instanceof Exception e) throw e;
        throw new RuntimeException(last);
    }

    private String callDeepSeekOnce(String prompt) throws Exception {
        String url = baseUrl + "/chat/completions";

        // 过滤控制字符，避免 DeepSeek 直接报 400
        String safeUserContent = sanitizeForJson(prompt);

        // OpenAI-style Chat Completions 请求体
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
                new ChatMessage("user", safeUserContent)
        );

        String payloadJson = om.writeValueAsString(reqPayload);

        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // 避免 gzip+chunked 组合在部分代理场景下的边缘问题
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .post(RequestBody.create(payloadJson, MEDIA_JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                String err = safeReadBody(resp.body());
                int code = resp.code();
                // 把 HTTP 状态编码进异常信息，供外层判定重试
                throw new IllegalStateException("DeepSeek HTTP " + code +
                        " TE=" + resp.header("Transfer-Encoding") +
                        " CE=" + resp.header("Content-Encoding") +
                        " body=" + err);
            }
            ResponseBody rb = resp.body();
            if (rb == null) throw new IllegalStateException("Empty body");
            byte[] bytes = rb.bytes(); // 避免 OkHttp 的 string() 做 charset 猜测
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

    private static boolean isRetryableThrowable(Throwable t) {
        String s = String.valueOf(t).toLowerCase();

        // 网络抖动类
        if (t instanceof java.io.InterruptedIOException) return true; // 含 SocketTimeout
        if (t instanceof java.net.SocketTimeoutException) return true;
        if (t instanceof java.net.ProtocolException) return true;
        if (s.contains("stream was reset")) return true;        // HTTP/2 CANCEL
        if (s.contains("canceled")) return true;                // okhttp 某些场景
        if (s.contains("timeout")) return true;
        if (s.contains("connection reset")) return true;

        // HTTP 状态：429/5xx 走重试（通过 message 文本判断）
        if (s.contains("http 429")) return true;
        if (s.contains("http 500") || s.contains("http 502") || s.contains("http 503") || s.contains("http 504")) return true;

        return false;
    }

    private static long randJitter(long max) {
        if (max <= 0) return 0;
        return (long) (Math.random() * (max + 1));
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
     * 解析 OpenAI-style：
     * {
     *   "choices": [
     *     { "message": { "content": "..." } }
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
            // 兜底：旧的手工提取方式
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
                if (c == '\\') { esc = true; continue; }
                if (c == '"') { break; }
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
            if (fence2 > fence) cleaned = cleaned.substring(fence + 3, fence2);
        }

        cleaned = cleaned.trim();
        int stObj = cleaned.indexOf('{');
        int stArr = cleaned.indexOf('[');

        try {
            if (stArr >= 0 && (stObj < 0 || stArr < stObj)) {
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

    /** 把 LLM 返回的 renames 数组转成 RenameSuggestion 列表 */
    private void appendRenames(List<RenameSuggestion> out, JsonNode arr) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) continue;
            String kind = pickText(n, "kind", "type");
            String oldN = pickText(n, "old", "from", "key", "name");
            String newN = pickText(n, "new", "to", "value", "rename");
            if (kind == null || oldN == null || newN == null) continue;

            RenameSuggestion r = new RenameSuggestion();
            r.kind = kind;
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

    /* =========================================================
     *                   工具函数
     * ========================================================= */

    /** 清理除 \n\r\t 外的控制字符，避免 JSON 解析问题 */
    private static String sanitizeForJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') sb.append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Chat Completions 风格的 message */
    public static class ChatMessage {
        public String role;
        public String content;
        public ChatMessage() {}
        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /** 发给 DeepSeek 的请求体 */
    public static class ChatRequest {
        public String model;
        public List<ChatMessage> messages;
        public double temperature;
        public boolean stream;
    }
}