package com.initialone.jhumanify.llm;

import okhttp3.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Minimal local OpenAI-compatible Chat client for doc generation. */
public class LocalDocClient implements DocClient {

    private final OkHttpClient http;
    private final String apiKey;   // optional for local servers
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;

    public LocalDocClient(String baseUrl, String model, int timeoutSeconds, int maxTokens, double temperature) {
        // 尝试从本地环境读取：优先 LOCAL_LLM_API_KEY，其次 OPENAI_API_KEY；本地多数情况不需要密钥
        String key = System.getenv("LOCAL_LLM_API_KEY");
        if (key == null || key.isEmpty()) {
            key = System.getenv("OPENAI_API_KEY");
        }
        this.apiKey = (key != null && !key.isEmpty()) ? key : null;

        this.baseUrl = (baseUrl == null || baseUrl.isEmpty()) ? "http://localhost:1234" : baseUrl;
        this.model = (model == null || model.isEmpty()) ? "local-model" : model;
        this.maxTokens = maxTokens <= 0 ? 256 : maxTokens;
        this.temperature = temperature;
        this.http = new OkHttpClient.Builder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build();
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
        String system = (lang.equals("zh")
                ? "请**严格使用简体中文**撰写.你是资深 Java 工程师。为给定 Java 代码生成 1-2 句 Javadoc 注释，不要杜撰外部行为。必要时简述副作用。只输出注释正文，不要包含 /** */ 符号。"
                : "Write the Javadoc comment in **English only**.You are a senior Java engineer. Generate a 1–2 sentence Javadoc comment for the given Java code. Do not hallucinate external behavior. Mention side-effects if obvious. Output only the comment body, no /** */ markers.");
        String user = "Style: " + style + "\nCode:\n```java\n" + code + "\n```";

        String json = "{"
                + "\"model\": \"" + model + "\","
                + "\"temperature\": " + temperature + ","
                + "\"max_tokens\": " + maxTokens + ","
                + "\"messages\":[{"
                + "\"role\":\"system\",\"content\":\""+escape(system)+"\"},{"
                + "\"role\":\"user\",\"content\":\""+escape(user)+"\"}]}";

        Request.Builder rb = new Request.Builder()
                .url(normalizeBase(baseUrl) + "/v1/chat/completions")
                .post(RequestBody.create(json.getBytes(StandardCharsets.UTF_8), MediaType.parse("application/json")));

        if (apiKey != null && !apiKey.isEmpty()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }

        Request req = rb.build();

        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) throw new RuntimeException("Local LLM error " + resp.code() + ": " + body);
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(body);
            String content = root.path("choices").path(0).path("message").path("content").asText().trim();
            return content.replaceAll("^/\\*\\*|\\*/$", "").trim();
        }
    }

    private static String normalizeBase(String base) {
        // 去掉末尾斜杠，确保后面拼接 /v1/chat/completions
        if (base.endsWith("/")) return base.substring(0, base.length() - 1);
        return base;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}