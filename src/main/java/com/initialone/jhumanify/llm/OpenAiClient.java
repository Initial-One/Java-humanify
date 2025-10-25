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
 * OpenAI / OpenAI 兼容 Chat Completions 客户端。
 *
 * 特点:
 * - 分批 (batchSize)
 * - 严格把返回内容解析成 RenameSuggestion[]
 * - 429 / 5xx 时指数退避重试
 * - 可用 OPENAI_BASE_URL 覆盖默认基地址 (默认 https://api.openai.com)
 *
 * 此版本的改动:
 * - 增加 sanitizeForJson(...)：把 APK 反编译代码里的不可打印控制字符过滤掉
 *   (除了 \n \r \t 之外的 0x00~0x1F 都会被替换成空格)，防止请求体里出现原始 NUL 等导致网关返回 400。
 * - 请求头增加 Accept / Accept-Encoding / Connection，和 DeepSeekClient 保持一致的健壮性。
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

        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override
    public List<RenameSuggestion> suggestRenames(List<String> snippets) throws Exception {
        List<RenameSuggestion> all = new ArrayList<>();
        for (int i = 0; i < snippets.size(); i += batchSize) {
            List<String> batch = snippets.subList(i, Math.min(i + batchSize, snippets.size()));

            // 构造 prompt (包含 META header + Java 代码)
            String prompt = buildPrompt(batch);

            // 调用 LLM，拿到这个 batch 的重命名建议
            List<RenameSuggestion> part = callChatCompletions(prompt);
            if (part != null) {
                all.addAll(part);
            }
        }
        return all;
    }

    /**
     * 构造发给模型的 prompt。
     *
     * 注意：这里我们还没 sanitize；我们会在真正发请求时调用 sanitizeForJson()，
     * 这样不会破坏你后续调试/日志里看到的原始 prompt。
     */
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
            sb.append(block).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 真正发起 Chat Completions 请求:
     * - 用 Jackson 构建/序列化 JSON，确保引号、换行、安全转义都正确
     * - 对 userPrompt 做 sanitizeForJson()，去掉不可打印控制字符，避免 400
     */
    private List<RenameSuggestion> callChatCompletions(String userPrompt) throws Exception {
        // 消毒，防止控制字符 (0x00~0x1F) 直接进 JSON
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
        // 不强制 response_format=json_object，因为不同模型/网关的支持度不完全一致；
        // 我们让 prompt 约束 "Output ONLY the JSON array".
        payload.put("max_tokens", 4096);

        // Jackson 负责做所有必要的字符串转义
        String json = om.writeValueAsString(payload);

        Request req = new Request.Builder()
                .url(chatUrl())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // 下面两行是为了行为更稳定（很多代理/gateway在gzip+keepalive+chunked下会搞怪）
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .post(RequestBody.create(json, MEDIA_JSON))
                .build();

        int attempts = 0;
        while (true) {
            attempts++;
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    // 429 / 5xx 简单重试 (指数退避 + 抖动)
                    if ((resp.code() == 429 || resp.code() >= 500) && attempts < 3) {
                        long backoff = (long) (500L * Math.pow(2, attempts - 1)
                                + new Random().nextInt(250));
                        Thread.sleep(backoff);
                        continue;
                    }
                    throw new RuntimeException("OpenAI HTTP " + resp.code() + ": " + safeTrim(body));
                }

                // 解析响应：choices[0].message.content
                JsonNode root = om.readTree(body);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.size() == 0) {
                    throw new RuntimeException("No choices in response");
                }
                String content = choices.get(0)
                        .path("message")
                        .path("content")
                        .asText("");

                if (content.isEmpty()) {
                    throw new RuntimeException("Empty content in response");
                }

                // content 应该是 JSON 数组；但有些模型偶尔会包对象或加解释文字
                String cleaned = extractJson(content);

                // 情况1: cleaned 是对象，可能长成 { "items":[ ... ] }
                if (cleaned.startsWith("{")) {
                    JsonNode node = om.readTree(cleaned);
                    if (node.has("items") && node.get("items").isArray()) {
                        return om.convertValue(
                                node.get("items"),
                                new TypeReference<List<RenameSuggestion>>() {}
                        );
                    }
                }

                // 情况2: cleaned 是数组本身
                return om.readValue(
                        cleaned.getBytes(StandardCharsets.UTF_8),
                        new TypeReference<List<RenameSuggestion>>() {}
                );
            }
        }
    }

    /**
     * 允许通过环境变量 OPENAI_BASE_URL 自定义基地址。
     * 例如:
     *   export OPENAI_BASE_URL=https://your-gateway.example.com
     * 会请求:
     *   $OPENAI_BASE_URL/v1/chat/completions
     *
     * 默认是 https://api.openai.com
     */
    private static String chatUrl() {
        String base = System.getenv("OPENAI_BASE_URL");
        if (base == null || base.isBlank()) {
            base = "https://api.openai.com";
        }
        // 去掉尾部斜杠，避免双斜杠
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/v1/chat/completions";
    }

    /**
     * 从 LLM 返回的 content 里提取最外层 JSON:
     * - 优先找数组 [...]，否则找对象 {...}。
     * - 如果模型加了前后废话，这里会把废话剥掉。
     */
    private static String extractJson(String s) {
        int i = s.indexOf('[');
        int j = s.lastIndexOf(']');
        if (i >= 0 && j > i) {
            return s.substring(i, j + 1);
        }

        int k1 = s.indexOf('{');
        int k2 = s.lastIndexOf('}');
        if (k1 >= 0 && k2 > k1) {
            return s.substring(k1, k2 + 1);
        }
        // fallback：尽量别返回空字符串，至少 trim 一下
        return s.trim();
    }

    /**
     * 只为了在报错时别打太长：去掉多余空白并截断。
     */
    private static String safeTrim(String s) {
        s = (s == null ? "" : s.replaceAll("\\s+", " "));
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /**
     * 把 prompt 里的 NUL、0x01 等奇怪控制字符清掉，防止下游 API 认为 JSON 非法。
     * - 保留 \n \r \t，因为这些是我们想传给模型的格式信息
     * - 其他 <0x20 的字符替换成空格
     *
     * 这样即使 APK 反编译代码里有那些不可见垃圾字符，也不会直接把它们塞进 JSON。
     */
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
}