package com.initialone.jhumanify.llm;

import com.initialone.jhumanify.model.RenameSuggestion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp 直连 OpenAI Chat Completions 的最小可用实现：
 * - 支持分批（batchSize 可调）
 * - JSON 严格解析为 RenameSuggestion[]
 * - 简单重试(429/5xx)
 *
 * 依赖：
 *   okhttp 4.12.x
 *   jackson-databind 2.17.x
 *
 * 配置：
 *   new OpenAiClient(System.getenv("OPENAI_API_KEY"), "gpt-4o-mini", 12)
 */
public class OpenAiClient implements LlmClient {
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String CHAT_URL = "https://api.openai.com/v1/chat/completions";

    private final OkHttpClient http;
    private final ObjectMapper om = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final int batchSize;

    public OpenAiClient(String apiKey, String model, int batchSize) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OPENAI_API_KEY is missing");
        }
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? "gpt-4o-mini" : model;
        this.batchSize = Math.max(1, batchSize);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override
    public List<RenameSuggestion> suggestRenames(List<String> snippets) throws Exception {
        List<RenameSuggestion> all = new ArrayList<>();
        for (int i = 0; i < snippets.size(); i += batchSize) {
            List<String> batch = snippets.subList(i, Math.min(i + batchSize, snippets.size()));
            String prompt = buildPrompt(batch);
            List<RenameSuggestion> part = callChatCompletions(prompt);
            if (part != null) all.addAll(part);
        }
        return all;
    }

    private String buildPrompt(List<String> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior Java reverse-engineering assistant.\n")
                .append("Each snippet starts with a META header containing: packageName, className, classFqn, methodName, methodSignature.\n")
                .append("Propose semantic renames as a STRICT JSON array; each item:\n")
                .append("{\"kind\":\"class|method|field|var\",\"oldName\":\"...\",\"newName\":\"...\",\"reason\":\"...\"}\n")
                .append("Rules:\n")
                .append("- For class renames, oldName MUST equal META.className (e.g., \"a\").\n")
                .append("- For method renames, oldName MUST equal META.methodName (e.g., \"O0O0\").\n")
                .append("- For fields/vars, oldName is the short identifier visible in the code body.\n")
                .append("- Use CamelCase for classes; lowerCamelCase for methods/vars/fields.\n")
                .append("- If META.className looks obfuscated (length ≤ 2 or matches [a-z]\\d+), strongly propose a better class name.\n")
                .append("- Derive semantics from types, call patterns, and string literals; keep package unchanged.\n\n")
                .append("Output ONLY the JSON array, no extra text.\n\n");

        int idx = 1;
        for (String block : batch) {
            sb.append("// SNIPPET #").append(idx++).append('\n');
            // 这里不要再套一层 ```，直接把带 META 的块贴上去
            sb.append(block).append("\n\n");
        }
        return sb.toString();
    }

    /** 调用 Chat Completions；严格提取 choices[0].message.content 并反序列化为 RenameSuggestion[] */
    private List<RenameSuggestion> callChatCompletions(String userPrompt) throws Exception {
        // messages: system + user
        Map<String, Object> sysMsg = Map.of("role", "system", "content",
                "You are an expert Java deobfuscation assistant.");
        Map<String, Object> userMsg = Map.of("role", "user", "content", userPrompt);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(sysMsg, userMsg));
        payload.put("temperature", 0.2);
        // JSON 模式：让模型只返回 JSON（部分模型支持）
        payload.put("response_format", Map.of("type", "json_object"));

        String json = om.writeValueAsString(payload);
        Request req = new Request.Builder()
                .url(CHAT_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, JSON))
                .build();

        int attempts = 0;
        while (true) {
            attempts++;
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    // 429/5xx 简单重试
                    if ((resp.code() == 429 || resp.code() >= 500) && attempts < 3) {
                        Thread.sleep(500L * attempts);
                        continue;
                    }
                    throw new RuntimeException("OpenAI HTTP " + resp.code() + ": " + safeTrim(body));
                }
                // 解析 choices[0].message.content
                JsonNode root = om.readTree(body);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.size() == 0) {
                    throw new RuntimeException("No choices in response");
                }
                String content = choices.get(0).path("message").path("content").asText("");
                if (content.isEmpty()) {
                    throw new RuntimeException("Empty content in response");
                }
                // content 里就是严格 JSON：数组或对象（数组优先）
                String cleaned = extractJson(content);
                if (cleaned.startsWith("{")) {
                    // 有些模型可能外面包了一层 {"items":[...]}
                    JsonNode node = om.readTree(cleaned);
                    if (node.has("items") && node.get("items").isArray()) {
                        return om.convertValue(node.get("items"),
                                new TypeReference<List<RenameSuggestion>>() {});
                    }
                }
                // 直接数组
                return om.readValue(cleaned.getBytes(StandardCharsets.UTF_8),
                        new TypeReference<List<RenameSuggestion>>() {});
            }
        }
    }

    /** 从 content 中取最外层 JSON（容错去掉模型偶尔加的说明文字） */
    private static String extractJson(String s) {
        int i = s.indexOf('['), j = s.lastIndexOf(']');
        if (i >= 0 && j > i) return s.substring(i, j + 1);
        int k1 = s.indexOf('{'), k2 = s.lastIndexOf('}');
        if (k1 >= 0 && k2 > k1) return s.substring(k1, k2 + 1);
        return s.trim();
    }

    private static String safeTrim(String s) {
        s = s == null ? "" : s.replaceAll("\\s+", " ");
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}