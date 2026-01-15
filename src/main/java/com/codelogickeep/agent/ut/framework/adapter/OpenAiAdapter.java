package com.codelogickeep.agent.ut.framework.adapter;

import com.codelogickeep.agent.ut.framework.executor.StreamingHandler;
import com.codelogickeep.agent.ut.framework.model.*;
import com.codelogickeep.agent.ut.framework.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容适配器 - 支持 OpenAI API 及兼容服务（智谱 AI 等）
 * 
 * 支持的服务：
 * - OpenAI (api.openai.com)
 * - 智谱 AI Coding Plan (open.bigmodel.cn)
 * - 其他 OpenAI 兼容服务
 */
public class OpenAiAdapter implements LlmAdapter {
    private static final Logger log = LoggerFactory.getLogger(OpenAiAdapter.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Double temperature;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final boolean logRequests;

    private OpenAiAdapter(Builder builder) {
        this.baseUrl = normalizeBaseUrl(builder.baseUrl);
        this.apiKey = builder.apiKey;
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.timeout = builder.timeout;
        this.logRequests = builder.logRequests;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 规范化 base URL
     */
    private String normalizeBaseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "https://api.openai.com/v1";
        }
        // 移除末尾斜杠
        url = url.replaceAll("/+$", "");
        // 如果没有 /v1 路径，添加它（除非是智谱等特殊 URL）
        if (!url.endsWith("/v1") && !url.contains("/paas/") && !url.contains("/coding/")) {
            url = url + "/v1";
        }
        return url;
    }

    @Override
    public String getName() {
        return "OpenAI Compatible";
    }

    @Override
    public AssistantMessage chat(List<Message> messages, List<ToolDefinition> tools) {
        String endpoint = baseUrl + "/chat/completions";
        String requestBody = JsonUtil.buildChatRequest(model, messages, tools, temperature, false);

        if (logRequests) {
            log.info("Request to {}", endpoint);
            // 打印消息结构（用于调试）
            log.info("Messages count: {}", messages.size());
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                String preview = msg.content() != null
                        ? msg.content().substring(0, Math.min(100, msg.content().length()))
                        : "(null)";
                log.info("  [{}] role={}, content preview: {}", i, msg.role(), preview);
                
                // 检查 AssistantMessage 是否有 tool_calls
                if (msg instanceof AssistantMessage am && am.hasToolCalls()) {
                    log.info("       -> tool_calls: {}", am.toolCalls().size());
                }
            }
        }
        
        // 始终打印完整请求体用于调试 1214 错误
        if (requestBody.length() < 10000) {
            log.debug("Full request body: {}", requestBody);
        } else {
            log.debug("Full request body (truncated): {}...", requestBody.substring(0, 5000));
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(timeout)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (logRequests) {
                log.info("Response: {}", response.body());
            }

            if (response.statusCode() != 200) {
                log.error("API error: {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("API error: " + response.statusCode() + " - " + response.body());
            }

            JsonNode jsonResponse = JsonUtil.parse(response.body());
            JsonNode choices = jsonResponse.get("choices");

            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new RuntimeException("Invalid response: no choices");
            }

            return JsonUtil.parseAssistantMessage(choices.get(0));

        } catch (Exception e) {
            log.error("Chat request failed", e);
            throw new RuntimeException("Chat request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void chatStream(List<Message> messages, List<ToolDefinition> tools, StreamingHandler handler) {
        String endpoint = baseUrl + "/chat/completions";
        String requestBody = JsonUtil.buildChatRequest(model, messages, tools, temperature, true);

        if (logRequests) {
            log.info("Streaming request to {}: {}", endpoint, requestBody);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(timeout)
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes());
                log.error("Streaming API error: {} - {}", response.statusCode(), errorBody);
                handler.onError(new RuntimeException("API error: " + response.statusCode() + " - " + errorBody));
                return;
            }

            // 解析 SSE 流
            parseSSEStream(response.body(), handler);

        } catch (Exception e) {
            log.error("Streaming request failed", e);
            handler.onError(e);
        }
    }

    /**
     * 解析 SSE 流
     */
    private void parseSSEStream(java.io.InputStream inputStream, StreamingHandler handler) {
        StringBuilder contentBuilder = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        Map<Integer, ToolCallBuilder> toolCallBuilders = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }

                if (!line.startsWith("data: ")) {
                    continue;
                }

                String data = line.substring(6).trim();

                if ("[DONE]".equals(data)) {
                    break;
                }

                try {
                    JsonNode jsonData = JsonUtil.parse(data);
                    JsonNode choices = jsonData.get("choices");

                    if (choices == null || !choices.isArray() || choices.isEmpty()) {
                        continue;
                    }

                    JsonNode choice = choices.get(0);
                    JsonNode delta = choice.get("delta");

                    if (delta == null) {
                        continue;
                    }

                    // 处理文本内容
                    if (delta.has("content") && !delta.get("content").isNull()) {
                        String content = delta.get("content").asText();
                        contentBuilder.append(content);
                        handler.onToken(content);
                    }

                    // 处理工具调用（流式增量）
                    if (delta.has("tool_calls")) {
                        JsonNode tcArray = delta.get("tool_calls");
                        for (JsonNode tcDelta : tcArray) {
                            int index = tcDelta.has("index") ? tcDelta.get("index").asInt() : 0;

                            ToolCallBuilder builder = toolCallBuilders.computeIfAbsent(index,
                                    k -> new ToolCallBuilder());

                            if (tcDelta.has("id")) {
                                builder.id = tcDelta.get("id").asText();
                            }

                            JsonNode function = tcDelta.get("function");
                            if (function != null) {
                                if (function.has("name")) {
                                    builder.name = function.get("name").asText();
                                }
                                if (function.has("arguments")) {
                                    builder.arguments.append(function.get("arguments").asText());
                                }
                            }
                        }
                    }

                    // 检查 finish_reason
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                        String finishReason = choice.get("finish_reason").asText();
                        if ("tool_calls".equals(finishReason) || "stop".equals(finishReason)) {
                            break;
                        }
                    }

                } catch (Exception e) {
                    log.debug("Failed to parse SSE data: {}", data, e);
                }
            }

            // 构建最终的工具调用列表
            for (Map.Entry<Integer, ToolCallBuilder> entry : toolCallBuilders.entrySet()) {
                ToolCall tc = entry.getValue().build();
                if (tc != null) {
                    toolCalls.add(tc);
                    handler.onToolCall(tc);
                }
            }

            // 完成
            handler.onComplete(contentBuilder.toString(), toolCalls.isEmpty() ? null : toolCalls);

        } catch (Exception e) {
            log.error("Failed to parse SSE stream", e);
            handler.onError(e);
        }
    }

    /**
     * 工具调用构建器（用于流式增量构建）
     */
    private static class ToolCallBuilder {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();

        ToolCall build() {
            if (name == null || name.isEmpty()) {
                return null;
            }

            Map<String, Object> args = new HashMap<>();
            String argsStr = arguments.toString();
            if (!argsStr.isEmpty()) {
                try {
                    JsonNode argsNode = JsonUtil.parse(argsStr);
                    argsNode.fields()
                            .forEachRemaining(entry -> args.put(entry.getKey(), nodeToValue(entry.getValue())));
                } catch (Exception e) {
                    // 解析失败
                }
            }

            return new ToolCall(id, name, args);
        }

        private Object nodeToValue(JsonNode node) {
            if (node.isTextual())
                return node.asText();
            if (node.isInt())
                return node.asInt();
            if (node.isLong())
                return node.asLong();
            if (node.isDouble())
                return node.asDouble();
            if (node.isBoolean())
                return node.asBoolean();
            if (node.isNull())
                return null;
            return node.toString();
        }
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String model;
        private Double temperature;
        private Duration timeout = Duration.ofSeconds(120);
        private boolean logRequests = false;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder logRequests(boolean log) {
            this.logRequests = log;
            return this;
        }

        public OpenAiAdapter build() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("API key is required");
            }
            if (model == null || model.isEmpty()) {
                throw new IllegalArgumentException("Model name is required");
            }
            return new OpenAiAdapter(this);
        }
    }
}
