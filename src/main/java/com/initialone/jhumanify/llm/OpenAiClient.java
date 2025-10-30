package com.initialone.jhumanify.llm;

import com.initialone.jhumanify.model.RenameSuggestion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * OpenAI / 兼容 Chat Completions 客户端（稳健版）。
 *
 * 特点：
 * - 分批（batchSize）
 * - 内容严格解析为 RenameSuggestion[]
 * - 429/408/5xx + 常见网络异常 => 指数退避 + 抖动重试（最多 5 次）
 * - 支持 OPENAI_BASE_URL 自定义（默认 https://api.openai.com）
 * - 过滤不可打印控制字符，避免请求体 JSON 违法
 */
public class OpenAiClient implements LlmClient {
    private static final MediaType MEDIA_JSON = MediaType.parse("application/json");

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

        // 更稳的 HTTP 配置
        this.http = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .callTimeout(210, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
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

    /** 构造提示词（不在这里 sanitize；发送前统一 sanitize） */
    private String buildPrompt(List<String> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior Java reverse-engineering assistant.\n")
                .append("Each snippet starts with a META header: packageName, className, classFqn, methodName, methodSignature.\n")
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
            sb.append(block).append("\n\n");
        }
        return sb.toString();
    }

    /** 发送 Chat Completions，请求稳健 + 重试 */
    private List<RenameSuggestion> callChatCompletions(String userPrompt) throws Exception {
        String safeUserPrompt = sanitizeForJson(userPrompt);

        Map<String, Object> sysMsg = Map.of(
                "role", "system",
                "content", "You are an expert Java deobfuscation assistant."
        );
        Map<String, Object> userMsg = Map.of(
                "role", "user",
                "content", safeUserPrompt
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(sysMsg, userMsg));
        payload.put("temperature", 0.2);
        payload.put("max_tokens", 4096);
        // 不强制 response_format，兼容性更好；靠提示约束只输出 JSON 数组

        String json = om.writeValueAsString(payload);

        Request req = new Request.Builder()
                .url(chatUrl())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // 避免某些代理在 gzip+keepalive+chunked 下的诡异问题
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .post(RequestBody.create(json, MEDIA_JSON))
                .build();

        final int maxAttempts = 5;
        long backoff = 600; // ms
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    int code = resp.code();
                    // 429/408/5xx 触发重试
                    if ((code == 429 || code == 408 || code >= 500) && attempt < maxAttempts) {
                        sleepWithJitter(backoff);
                        backoff = Math.min(backoff * 2, 4000);
                        continue;
                    }
                    throw new RuntimeException("OpenAI HTTP " + code + ": " + safeTrim(body));
                }

                // 解析：choices[0].message.content
                JsonNode root = om.readTree(body);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.size() == 0) {
                    throw new RuntimeException("No choices in response");
                }
                String content = choices.get(0).path("message").path("content").asText("");
                if (content.isEmpty()) {
                    throw new RuntimeException("Empty content in response");
                }

                String cleaned = extractJson(content); // 支持 fenced ```json
                // 对象包装 { items:[...] } 也兼容
                if (cleaned.startsWith("{")) {
                    JsonNode node = om.readTree(cleaned);
                    if (node.has("items") && node.get("items").isArray()) {
                        return om.convertValue(node.get("items"),
                                new TypeReference<List<RenameSuggestion>>() {});
                    }
                }
                // 直接数组
                return om.readValue(cleaned.getBytes(StandardCharsets.UTF_8),
                        new TypeReference<List<RenameSuggestion>>() {});
            } catch (java.io.InterruptedIOException |
                     java.net.ProtocolException e) {
                if (attempt < maxAttempts) {
                    sleepWithJitter(backoff);
                    backoff = Math.min(backoff * 2, 4000);
                    continue;
                }
                throw e;
            } catch (IOException e) {
                // OkHttp/HTTP2: StreamResetException: CANCEL 等
                if (attempt < maxAttempts) {
                    sleepWithJitter(backoff);
                    backoff = Math.min(backoff * 2, 4000);
                    continue;
                }
                throw e;
            }
        }
        // 理论到不了
        throw new IllegalStateException("Exhausted retries");
    }

    /** 允许通过环境变量 OPENAI_BASE_URL 覆盖，最终访问 /v1/chat/completions */
    private static String chatUrl() {
        String base = System.getenv("OPENAI_BASE_URL");
        if (base == null || base.isBlank()) base = "https://api.openai.com";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/v1/chat/completions";
    }

    /** 从返回内容中提取最外层 JSON，支持 ```json fenced```，优先数组，其次对象 */
    private static String extractJson(String s) {
        String t = s.trim();

        // 去掉 ```json ... ``` 或 ``` ... ``` 包裹
        int f1 = t.indexOf("```");
        if (f1 >= 0) {
            int f2 = t.indexOf("```", f1 + 3);
            if (f2 > f1) {
                t = t.substring(f1 + 3, f2).trim();
                // 去掉可能的 "json" 语言标签
                if (t.startsWith("json")) {
                    t = t.substring(4).trim();
                }
            }
        }

        int i = t.indexOf('['), j = t.lastIndexOf(']');
        if (i >= 0 && j > i) return t.substring(i, j + 1);

        int k1 = t.indexOf('{'), k2 = t.lastIndexOf('}');
        if (k1 >= 0 && k2 > k1) return t.substring(k1, k2 + 1);

        return t;
    }

    /** 错误信息截断，避免日志刷屏 */
    private static String safeTrim(String s) {
        s = (s == null ? "" : s.replaceAll("\\s+", " "));
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /** 清理控制字符（保留 \n \r \t） */
    private static String sanitizeForJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int idx = 0; idx < s.length(); idx++) {
            char c = s.charAt(idx);
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

    /** 带抖动的休眠 */
    private static void sleepWithJitter(long baseMs) {
        long jitter = ThreadLocalRandom.current().nextLong(0, 250);
        try {
            Thread.sleep(baseMs + jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}