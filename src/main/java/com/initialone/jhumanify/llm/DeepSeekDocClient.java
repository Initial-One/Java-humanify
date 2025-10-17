package com.initialone.jhumanify.llm;

import okhttp3.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Minimal DeepSeek Chat client specialized for doc generation. */
public class DeepSeekDocClient implements DocClient {

    private final OkHttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final ObjectMapper om = new ObjectMapper();

    public DeepSeekDocClient(String baseUrl, String model, int timeoutSeconds, int maxTokens, double temperature) {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isEmpty()) {
            throw new IllegalStateException("[DeepSeekDocClient] DEEPSEEK_API_KEY missing");
        }
        this.apiKey = key;
        this.baseUrl = (baseUrl == null || baseUrl.isEmpty()) ? "https://api.deepseek.com" : trimTrailingSlash(baseUrl);
        this.model = (model == null || model.isEmpty()) ? "deepseek-chat" : model;
        this.maxTokens = maxTokens <= 0 ? 256 : maxTokens;
        this.temperature = temperature;

        this.http = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(Duration.ofSeconds(20))
                .readTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .writeTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .callTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds) + 30))
                // 优先 HTTP/2（避免 chunked），必要时回退到 HTTP/1.1
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build();
    }

    @Override
    public List<String> summarizeDocs(List<String> snippets, String lang, String style) throws Exception {
        List<String> out = new ArrayList<>();
        for (String sn : snippets) {
            out.add(callOnce(sn, lang, style));
        }
        return out;
    }

    private String callOnce(String code, String lang, String style) throws Exception {
        String system = (lang != null && lang.equals("zh"))
                ? "你是资深 Java 工程师。为给定 Java 代码生成 1-2 句 Javadoc 注释，不要杜撰外部行为。必要时简述副作用。只输出注释正文，不要包含 /** */ 符号。"
                : "You are a senior Java engineer. Generate a 1–2 sentence Javadoc comment for the given Java code. Do not hallucinate external behavior. Mention side-effects if obvious. Output only the comment body, no /** */ markers.";

        String user = "Style: " + (style == null ? "concise" : style) + "\nCode:\n```java\n" + (code == null ? "" : code) + "\n```";

        String payload = "{"
                + "\"model\":\"" + esc(model) + "\","
                + "\"temperature\":" + temperature + ","
                + "\"max_tokens\":" + maxTokens + ","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + esc(system) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + esc(user) + "\"}"
                + "]"
                + "}";

        Request req = new Request.Builder()
                .url(baseUrlWithV1(baseUrl) + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // 关键：避免 gzip+chunked 边缘问题，且不复用可能的陈旧连接
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .post(RequestBody.create(payload, MediaType.parse("application/json")))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                String err = safeReadBody(resp.body());
                throw new RuntimeException("DeepSeek error " + resp.code()
                        + " TE=" + resp.header("Transfer-Encoding")
                        + " CE=" + resp.header("Content-Encoding")
                        + " body=" + err);
            }
            ResponseBody rb = resp.body();
            if (rb == null) throw new RuntimeException("DeepSeek empty body");
            byte[] bytes = rb.bytes(); // 避免 ResponseBody.string() 的 BOM/charset 探测路径
            MediaType mt = rb.contentType();
            Charset cs = (mt != null && mt.charset() != null) ? mt.charset() : StandardCharsets.UTF_8;
            String body = new String(bytes, cs);

            // 解析
            String content = extractContent(body);
            return cleanupJavadoc(content);
        }
    }

    /* -------------------- helpers -------------------- */

    private static String safeReadBody(ResponseBody b) {
        if (b == null) return "";
        try {
            return new String(b.bytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<" + e.getClass().getSimpleName() + ">";
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String trimTrailingSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String baseUrlWithV1(String base) {
        return base.endsWith("/v1") ? base : base + "/v1";
    }

    /** 从标准/近似 OpenAI 兼容响应中提取 content，必要时回退到 body 原文。 */
    private String extractContent(String body) {
        try {
            JsonNode root = om.readTree(body);
            // 常见位置
            JsonNode node = root.path("choices").path(0).path("message").path("content");
            if (!node.isMissingNode() && !node.isNull() && node.isValueNode()) {
                return node.asText();
            }
            // 有些返回 text 字段
            node = root.path("choices").path(0).path("text");
            if (!node.isMissingNode() && !node.isNull() && node.isValueNode()) {
                return node.asText();
            }
            return body;
        } catch (Exception e) {
            // 解析失败直接把原文给清洗器
            return body;
        }
    }

    /** 去掉 ``` 包裹、Javadoc 标记与行首星号。 */
    private static String cleanupJavadoc(String raw) {
        if (raw == null) return "";
        String s = raw.trim();

        // 去掉 ```json/```java 包裹
        int f1 = s.indexOf("```");
        if (f1 >= 0) {
            int f2 = s.indexOf("```", f1 + 3);
            if (f2 > f1) s = s.substring(f1 + 3, f2).trim();
        }

        // 去掉开头/结尾的 /** */，以及行首的 *
        s = s.replaceAll("^/\\*\\*\\s*", "");
        s = s.replaceAll("\\s*\\*/$", "");
        s = s.replaceAll("(?m)^\\s*\\*\\s?", "");

        // 简单收尾
        return s.trim();
    }
}