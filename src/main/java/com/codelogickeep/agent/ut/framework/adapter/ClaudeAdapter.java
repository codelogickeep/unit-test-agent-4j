package com.codelogickeep.agent.ut.framework.adapter;

import com.codelogickeep.agent.ut.framework.executor.StreamingHandler;
import com.codelogickeep.agent.ut.framework.model.*;
import com.codelogickeep.agent.ut.framework.model.ToolDefinition.PropertySchema;
import com.codelogickeep.agent.ut.framework.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Claude (Anthropic) 适配器
 * 
 * Anthropic API 与 OpenAI 有一些关键区别：
 * - System 消息作为单独字段传递
 * - 工具定义格式略有不同
 * - 流式响应格式不同
 */
public class ClaudeAdapter implements LlmAdapter {
    private static final Logger log = LoggerFactory.getLogger(ClaudeAdapter.class);
    
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Double temperature;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final boolean logRequests;
    private final int maxTokens;
    
    private ClaudeAdapter(Builder builder) {
        this.baseUrl = builder.baseUrl != null ? builder.baseUrl : "https://api.anthropic.com";
        this.apiKey = builder.apiKey;
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.timeout = builder.timeout;
        this.logRequests = builder.logRequests;
        this.maxTokens = builder.maxTokens;
        
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }
    
    @Override
    public String getName() {
        return "Claude (Anthropic)";
    }
    
    @Override
    public AssistantMessage chat(List<Message> messages, List<ToolDefinition> tools) {
        String endpoint = baseUrl + "/v1/messages";
        String requestBody = buildClaudeRequest(messages, tools, false);
        
        if (logRequests) {
            log.info("Request to {}: {}", endpoint, requestBody);
        }
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
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
            
            return parseClaudeResponse(response.body());
            
        } catch (Exception e) {
            log.error("Chat request failed", e);
            throw new RuntimeException("Chat request failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void chatStream(List<Message> messages, List<ToolDefinition> tools, StreamingHandler handler) {
        String endpoint = baseUrl + "/v1/messages";
        String requestBody = buildClaudeRequest(messages, tools, true);
        
        if (logRequests) {
            log.info("Streaming request to {}: {}", endpoint, requestBody);
        }
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
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
            
            parseClaudeSSEStream(response.body(), handler);
            
        } catch (Exception e) {
            log.error("Streaming request failed", e);
            handler.onError(e);
        }
    }
    
    /**
     * 构建 Claude API 请求体
     */
    private String buildClaudeRequest(List<Message> messages, List<ToolDefinition> tools, boolean stream) {
        ObjectNode request = JsonUtil.getMapper().createObjectNode();
        request.put("model", model);
        request.put("max_tokens", maxTokens);
        
        if (temperature != null) {
            request.put("temperature", temperature);
        }
        
        if (stream) {
            request.put("stream", true);
        }
        
        // Claude 的 system 消息是单独字段
        String systemContent = null;
        ArrayNode messagesArray = JsonUtil.getMapper().createArrayNode();
        
        for (Message msg : messages) {
            if (msg instanceof SystemMessage sys) {
                systemContent = sys.content();
            } else {
                messagesArray.add(messageToClaudeFormat(msg));
            }
        }
        
        if (systemContent != null) {
            request.put("system", systemContent);
        }
        
        request.set("messages", messagesArray);
        
        // 工具定义
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = JsonUtil.getMapper().createArrayNode();
            for (ToolDefinition tool : tools) {
                toolsArray.add(toolToClaudeFormat(tool));
            }
            request.set("tools", toolsArray);
        }
        
        try {
            return JsonUtil.toJson(request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request", e);
        }
    }
    
    /**
     * 转换消息为 Claude 格式
     */
    private ObjectNode messageToClaudeFormat(Message msg) {
        ObjectNode node = JsonUtil.getMapper().createObjectNode();
        
        switch (msg) {
            case UserMessage user -> {
                node.put("role", "user");
                node.put("content", user.content());
            }
            case AssistantMessage assistant -> {
                node.put("role", "assistant");
                
                // Claude 使用 content 数组
                ArrayNode contentArray = JsonUtil.getMapper().createArrayNode();
                
                if (assistant.content() != null && !assistant.content().isEmpty()) {
                    ObjectNode textBlock = JsonUtil.getMapper().createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", assistant.content());
                    contentArray.add(textBlock);
                }
                
                if (assistant.hasToolCalls()) {
                    for (ToolCall tc : assistant.toolCalls()) {
                        ObjectNode toolUseBlock = JsonUtil.getMapper().createObjectNode();
                        toolUseBlock.put("type", "tool_use");
                        toolUseBlock.put("id", tc.id());
                        toolUseBlock.put("name", tc.name());
                        toolUseBlock.set("input", JsonUtil.getMapper().valueToTree(tc.arguments()));
                        contentArray.add(toolUseBlock);
                    }
                }
                
                node.set("content", contentArray);
            }
            case ToolMessage tool -> {
                node.put("role", "user");
                ArrayNode contentArray = JsonUtil.getMapper().createArrayNode();
                ObjectNode toolResultBlock = JsonUtil.getMapper().createObjectNode();
                toolResultBlock.put("type", "tool_result");
                toolResultBlock.put("tool_use_id", tool.toolCallId());
                toolResultBlock.put("content", tool.content());
                contentArray.add(toolResultBlock);
                node.set("content", contentArray);
            }
            default -> throw new IllegalArgumentException("Unexpected message type: " + msg.getClass());
        }
        
        return node;
    }
    
    /**
     * 转换工具定义为 Claude 格式
     */
    private ObjectNode toolToClaudeFormat(ToolDefinition tool) {
        ObjectNode node = JsonUtil.getMapper().createObjectNode();
        node.put("name", tool.name());
        node.put("description", tool.description());
        
        // Claude 的参数 schema 格式
        ObjectNode inputSchema = JsonUtil.getMapper().createObjectNode();
        inputSchema.put("type", "object");
        
        if (tool.parameters() != null && tool.parameters().properties() != null) {
            ObjectNode properties = JsonUtil.getMapper().createObjectNode();
            for (Map.Entry<String, PropertySchema> entry : tool.parameters().properties().entrySet()) {
                ObjectNode prop = JsonUtil.getMapper().createObjectNode();
                prop.put("type", entry.getValue().type());
                prop.put("description", entry.getValue().description());
                properties.set(entry.getKey(), prop);
            }
            inputSchema.set("properties", properties);
            
            if (tool.parameters().required() != null) {
                ArrayNode required = JsonUtil.getMapper().createArrayNode();
                tool.parameters().required().forEach(required::add);
                inputSchema.set("required", required);
            }
        }
        
        node.set("input_schema", inputSchema);
        return node;
    }
    
    /**
     * 解析 Claude API 响应
     */
    private AssistantMessage parseClaudeResponse(String responseBody) throws Exception {
        JsonNode json = JsonUtil.parse(responseBody);
        
        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        
        JsonNode contentArray = json.get("content");
        if (contentArray != null && contentArray.isArray()) {
            for (JsonNode block : contentArray) {
                String type = block.get("type").asText();
                
                if ("text".equals(type)) {
                    content.append(block.get("text").asText());
                } else if ("tool_use".equals(type)) {
                    String id = block.get("id").asText();
                    String name = block.get("name").asText();
                    Map<String, Object> input = new HashMap<>();
                    
                    JsonNode inputNode = block.get("input");
                    if (inputNode != null) {
                        inputNode.fields().forEachRemaining(entry -> 
                                input.put(entry.getKey(), nodeToValue(entry.getValue())));
                    }
                    
                    toolCalls.add(new ToolCall(id, name, input));
                }
            }
        }
        
        return new AssistantMessage(content.toString(), toolCalls.isEmpty() ? null : toolCalls);
    }
    
    /**
     * 解析 Claude SSE 流
     */
    private void parseClaudeSSEStream(java.io.InputStream inputStream, StreamingHandler handler) {
        StringBuilder contentBuilder = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        Map<Integer, ToolCallBuilder> toolBuilders = new HashMap<>();
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                
                String data = line.substring(6).trim();
                if (data.isEmpty()) {
                    continue;
                }
                
                try {
                    JsonNode event = JsonUtil.parse(data);
                    String type = event.has("type") ? event.get("type").asText() : "";
                    
                    switch (type) {
                        case "content_block_delta" -> {
                            JsonNode delta = event.get("delta");
                            if (delta != null) {
                                String deltaType = delta.get("type").asText();
                                if ("text_delta".equals(deltaType)) {
                                    String text = delta.get("text").asText();
                                    contentBuilder.append(text);
                                    handler.onToken(text);
                                } else if ("input_json_delta".equals(deltaType)) {
                                    int index = event.get("index").asInt();
                                    String partialJson = delta.get("partial_json").asText();
                                    toolBuilders.computeIfAbsent(index, k -> new ToolCallBuilder())
                                            .arguments.append(partialJson);
                                }
                            }
                        }
                        case "content_block_start" -> {
                            JsonNode contentBlock = event.get("content_block");
                            if (contentBlock != null && "tool_use".equals(contentBlock.get("type").asText())) {
                                int index = event.get("index").asInt();
                                ToolCallBuilder builder = toolBuilders.computeIfAbsent(index, 
                                        k -> new ToolCallBuilder());
                                builder.id = contentBlock.get("id").asText();
                                builder.name = contentBlock.get("name").asText();
                            }
                        }
                        case "message_stop" -> {
                            // 消息结束
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse event: {}", data, e);
                }
            }
            
            // 构建工具调用
            for (ToolCallBuilder builder : toolBuilders.values()) {
                ToolCall tc = builder.build();
                if (tc != null) {
                    toolCalls.add(tc);
                    handler.onToolCall(tc);
                }
            }
            
            handler.onComplete(contentBuilder.toString(), toolCalls.isEmpty() ? null : toolCalls);
            
        } catch (Exception e) {
            log.error("Failed to parse Claude SSE stream", e);
            handler.onError(e);
        }
    }
    
    private Object nodeToValue(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNull()) return null;
        return node.toString();
    }
    
    /**
     * 工具调用构建器
     */
    private static class ToolCallBuilder {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();
        
        ToolCall build() {
            if (name == null) return null;
            
            Map<String, Object> args = new HashMap<>();
            try {
                JsonNode argsNode = JsonUtil.parse(arguments.toString());
                argsNode.fields().forEachRemaining(entry -> {
                    JsonNode val = entry.getValue();
                    if (val.isTextual()) args.put(entry.getKey(), val.asText());
                    else if (val.isInt()) args.put(entry.getKey(), val.asInt());
                    else if (val.isDouble()) args.put(entry.getKey(), val.asDouble());
                    else if (val.isBoolean()) args.put(entry.getKey(), val.asBoolean());
                    else args.put(entry.getKey(), val.toString());
                });
            } catch (Exception e) {
                // 忽略解析错误
            }
            
            return new ToolCall(id, name, args);
        }
    }
    
    // ==================== Builder ====================
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String model = "claude-3-5-sonnet-20241022";
        private Double temperature;
        private Duration timeout = Duration.ofSeconds(120);
        private boolean logRequests = false;
        private int maxTokens = 8192;
        
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
        
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }
        
        public ClaudeAdapter build() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("API key is required");
            }
            return new ClaudeAdapter(this);
        }
    }
}
