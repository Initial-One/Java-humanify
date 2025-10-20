package com.initialone.jhumanify.llm;

import okhttp3.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** OpenAI Chat client for doc generation (robust version). */
public class OpenAiDocClient implements DocClient {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final ObjectMapper om = new ObjectMapper();

    /** 兼容旧用法：从环境变量读取 apiKey，baseUrl 走 OPENAI_BASE_URL 或 https://api.openai.com */
    public OpenAiDocClient(String model, int timeoutSeconds, int maxTokens, double temperature) {
        this(System.getenv("OPENAI_API_KEY"),
                envOr("OPENAI_BASE_URL", "https://api.openai.com"),
                model, timeoutSeconds, maxTokens, temperature);
    }

    /** 新：显式传入 apiKey/baseUrl 的构造器（便于 Humanify/Annotate 传入 --endpoint） */
    public OpenAiDocClient(String apiKey, String baseUrl, String model, int timeoutSeconds, int maxTokens, double temperature) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("[OpenAiDocClient] OPENAI_API_KEY missing");
        }
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.openai.com" : stripTrailingSlash(baseUrl);
        this.model = (model == null || model.isBlank()) ? "gpt-4o-mini" : model;
        this.maxTokens = maxTokens <= 0 ? 256 : maxTokens;
        this.temperature = temperature;

        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)  // 整体超时
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override
    public List<String> summarizeDocs(List<String> snippets, String lang, String style) throws Exception {
        List<String> out = new ArrayList<>(snippets.size());
        for (String sn : snippets) {
            out.add(callOnce(sn, lang, style));
        }
        return out;
    }

    private String callOnce(String code, String lang, String style) throws Exception {
        final String system = "zh".equalsIgnoreCase(lang)
                ? "请**严格使用简体中文**撰写.你是资深 Java 工程师。为给定 Java 代码生成 1-2 句 Javadoc 注释，不要杜撰外部行为。必要时简述副作用。只输出注释正文，不要包含 /** */ 符号。"
                : "Write the Javadoc comment in **English only**.You are a senior Java engineer. Generate a 1–2 sentence Javadoc comment for the given Java code. Do not hallucinate external behavior. Mention side-effects if obvious. Output only the comment body, no /** */ markers.";
        final String user = "Style: " + (style == null ? "concise" : style)
                + "\nCode:\n```java\n" + code + "\n```";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", temperature);
        payload.put("max_tokens", Math.max(1, maxTokens));
        payload.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user",   "content", user)
        ));
        String json = om.writeValueAsString(payload);

        Request req = new Request.Builder()
                .url(baseUrl + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, JSON))
                .build();

        int attempts = 0, maxAttempts = 3;
        while (true) {
            attempts++;
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() == null ? "" : resp.body().string();
                if (!resp.isSuccessful()) {
                    int codeHttp = resp.code();
                    if (shouldRetry(codeHttp) && attempts < maxAttempts) {
                        sleepBackoff(resp.headers(), attempts);
                        continue;
                    }
                    throw new RuntimeException("OpenAI error " + codeHttp + ": " + safeTrim(body));
                }
                JsonNode root = om.readTree(body);
                String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
                if (content.isEmpty()) throw new RuntimeException("Empty content in response");

                return sanitizeDoc(content);
            } catch (IOException ioe) {
                if (attempts < maxAttempts) {
                    sleepBackoff(null, attempts);
                    continue;
                }
                throw new RuntimeException("OpenAI IO error: " + ioe.getMessage(), ioe);
            }
        }
    }

    /* ===== helpers ===== */

    private static boolean shouldRetry(int code) {
        return code == 408 || code == 429 || code >= 500;
    }

    private static void sleepBackoff(Headers headers, int attempts) {
        long delayMs = -1;
        if (headers != null) {
            String ra = headers.get("Retry-After");
            if (ra != null) {
                try {
                    delayMs = (long) (Double.parseDouble(ra) * 1000L);
                } catch (NumberFormatException ignored) {}
            }
        }
        if (delayMs < 0) {
            delayMs = (long) (500L * Math.pow(2, attempts - 1) + new Random().nextInt(250));
            delayMs = Math.min(delayMs, 10_000L);
        }
        try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /** 清理可能的代码块与注释包裹，只保留注释正文 */
    private static String sanitizeDoc(String s) {
        String t = s.trim();

        // 去掉 ``` 包裹（含 ```java）
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            if (first > 0) t = t.substring(first + 1);
            int lastTicks = t.lastIndexOf("```");
            if (lastTicks >= 0) t = t.substring(0, lastTicks);
            t = t.trim();
        }

        // 去掉 /** ... */ 外壳
        if (t.startsWith("/**")) {
            int end = t.lastIndexOf("*/");
            if (end >= 0) t = t.substring(3, end).trim();
        }

        // 去掉每行前导 "*"
        StringBuilder sb = new StringBuilder();
        for (String line : t.split("\\r?\\n")) {
            String ln = line.trim();
            if (ln.startsWith("*")) ln = ln.substring(1).trim();
            sb.append(ln).append('\n');
        }
        t = sb.toString().trim();

        // 最终再规整一下空白
        return t.replaceAll("\\s+\\n", "\n").trim();
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static String stripTrailingSlash(String u) {
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String safeTrim(String s) {
        s = s == null ? "" : s.replaceAll("\\s+", " ");
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}