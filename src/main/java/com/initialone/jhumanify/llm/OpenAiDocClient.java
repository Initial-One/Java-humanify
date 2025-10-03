package com.initialone.jhumanify.llm;

import okhttp3.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Minimal OpenAI Chat client specialized for doc generation. */
public class OpenAiDocClient implements DocClient {

    private final OkHttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;

    public OpenAiDocClient(String model, int timeoutSeconds, int maxTokens, double temperature) {
        // 如果未传入 apiKey，则自动从环境变量获取
        apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("[OpenAiDocClient] OPENAI_API_KEY missing");
        }
        this.baseUrl = "https://api.openai.com";
        this.model = model == null ? "gpt-4o-mini" : model;
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
                ? "你是资深 Java 工程师。为给定 Java 代码生成 1-2 句 Javadoc 注释，不要杜撰外部行为。必要时简述副作用。只输出注释正文，不要包含 /** */ 符号。"
                : "You are a senior Java engineer. Generate a 1–2 sentence Javadoc comment for the given Java code. Do not hallucinate external behavior. Mention side-effects if obvious. Output only the comment body, no /** */ markers.");
        String user = "Style: " + style + "\nCode:\n```java\n" + code + "\n```";

        String json = "{"
                + "\"model\": \"" + model + "\","
                + "\"temperature\": " + temperature + ","
                + "\"max_tokens\": " + maxTokens + ","
                + "\"messages\":[{"
                + "\"role\":\"system\",\"content\":\""+escape(system)+"\"},{"
                + "\"role\":\"user\",\"content\":\""+escape(user)+"\"}]}";
        Request req = new Request.Builder()
                .url(baseUrl + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(json.getBytes(StandardCharsets.UTF_8), MediaType.parse("application/json")))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) throw new RuntimeException("OpenAI error " + resp.code() + ": " + body);
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(body);
            String content = root.path("choices").path(0).path("message").path("content").asText().trim();
            return content.replaceAll("^/\\*\\*|\\*/$", "").trim();
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}