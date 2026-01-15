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
 * Google Gemini 适配器
 * 
 * Gemini API 特点：
 * - 使用 generateContent 端点
 * - 消息格式为 contents 数组
 * - 工具定义放在 tools 字段
 * - 流式使用 streamGenerateContent
 */
public class GeminiAdapter implements LlmAdapter {
    private static final Logger log = LoggerFactory.getLogger(GeminiAdapter.class);
    
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Double temperature;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final boolean logRequests;
    
    private GeminiAdapter(Builder builder) {
        String url = builder.baseUrl != null ? builder.baseUrl : "https://generativelanguage.googleapis.com";
        // 确保有 v1beta 路径
        if (!url.endsWith("/v1beta") && !url.endsWith("/v1beta/")) {
            url = url.replaceAll("/+$", "") + "/v1beta";
        }
        this.baseUrl = url;
        this.apiKey = builder.apiKey;
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.timeout = builder.timeout;
        this.logRequests = builder.logRequests;
        
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }
    
    @Override
    public String getName() {
        return "Google Gemini";
    }
    
    @Override
    public AssistantMessage chat(List<Message> messages, List<ToolDefinition> tools) {
        String endpoint = String.format("%s/models/%s:generateContent?key=%s", baseUrl, model, apiKey);
        String requestBody = buildGeminiRequest(messages, tools);
        
        if (logRequests) {
            log.info("Request to {}: {}", endpoint.replace(apiKey, "***"), requestBody);
        }
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
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
            
            return parseGeminiResponse(response.body());
            
        } catch (Exception e) {
            log.error("Chat request failed", e);
            throw new RuntimeException("Chat request failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void chatStream(List<Message> messages, List<ToolDefinition> tools, StreamingHandler handler) {
        String endpoint = String.format("%s/models/%s:streamGenerateContent?alt=sse&key=%s", 
                baseUrl, model, apiKey);
        String requestBody = buildGeminiRequest(messages, tools);
        
        if (logRequests) {
            log.info("Streaming request to {}", endpoint.replace(apiKey, "***"));
        }
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
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
            
            parseGeminiSSEStream(response.body(), handler);
            
        } catch (Exception e) {
            log.error("Streaming request failed", e);
            handler.onError(e);
        }
    }
    
    /**
     * 构建 Gemini API 请求
     */
    private String buildGeminiRequest(List<Message> messages, List<ToolDefinition> tools) {
        ObjectNode request = JsonUtil.getMapper().createObjectNode();
        
        // 转换消息
        ArrayNode contents = JsonUtil.getMapper().createArrayNode();
        String systemInstruction = null;
        
        for (Message msg : messages) {
            if (msg instanceof SystemMessage sys) {
                systemInstruction = sys.content();
            } else {
                contents.add(messageToGeminiFormat(msg));
            }
        }
        
        request.set("contents", contents);
        
        // System instruction
        if (systemInstruction != null) {
            ObjectNode sysInst = JsonUtil.getMapper().createObjectNode();
            ArrayNode parts = JsonUtil.getMapper().createArrayNode();
            ObjectNode textPart = JsonUtil.getMapper().createObjectNode();
            textPart.put("text", systemInstruction);
            parts.add(textPart);
            sysInst.set("parts", parts);
            request.set("systemInstruction", sysInst);
        }
        
        // 工具定义
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = JsonUtil.getMapper().createArrayNode();
            ObjectNode toolDeclarations = JsonUtil.getMapper().createObjectNode();
            ArrayNode functionDeclarations = JsonUtil.getMapper().createArrayNode();
            
            for (ToolDefinition tool : tools) {
                functionDeclarations.add(toolToGeminiFormat(tool));
            }
            
            toolDeclarations.set("functionDeclarations", functionDeclarations);
            toolsArray.add(toolDeclarations);
            request.set("tools", toolsArray);
        }
        
        // 生成配置
        ObjectNode generationConfig = JsonUtil.getMapper().createObjectNode();
        if (temperature != null) {
            generationConfig.put("temperature", temperature);
        }
        request.set("generationConfig", generationConfig);
        
        try {
            return JsonUtil.toJson(request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request", e);
        }
    }
    
    /**
     * 转换消息为 Gemini 格式
     */
    private ObjectNode messageToGeminiFormat(Message msg) {
        ObjectNode node = JsonUtil.getMapper().createObjectNode();
        ArrayNode parts = JsonUtil.getMapper().createArrayNode();
        
        switch (msg) {
            case UserMessage user -> {
                node.put("role", "user");
                ObjectNode textPart = JsonUtil.getMapper().createObjectNode();
                textPart.put("text", user.content());
                parts.add(textPart);
            }
            case AssistantMessage assistant -> {
                node.put("role", "model");
                
                if (assistant.content() != null && !assistant.content().isEmpty()) {
                    ObjectNode textPart = JsonUtil.getMapper().createObjectNode();
                    textPart.put("text", assistant.content());
                    parts.add(textPart);
                }
                
                if (assistant.hasToolCalls()) {
                    for (ToolCall tc : assistant.toolCalls()) {
                        ObjectNode functionCall = JsonUtil.getMapper().createObjectNode();
                        ObjectNode fcNode = JsonUtil.getMapper().createObjectNode();
                        fcNode.put("name", tc.name());
                        fcNode.set("args", JsonUtil.getMapper().valueToTree(tc.arguments()));
                        functionCall.set("functionCall", fcNode);
                        parts.add(functionCall);
                    }
                }
            }
            case ToolMessage tool -> {
                node.put("role", "user");
                ObjectNode functionResponse = JsonUtil.getMapper().createObjectNode();
                ObjectNode frNode = JsonUtil.getMapper().createObjectNode();
                frNode.put("name", tool.name());
                ObjectNode response = JsonUtil.getMapper().createObjectNode();
                response.put("result", tool.content());
                frNode.set("response", response);
                functionResponse.set("functionResponse", frNode);
                parts.add(functionResponse);
            }
            default -> throw new IllegalArgumentException("Unexpected: " + msg.getClass());
        }
        
        node.set("parts", parts);
        return node;
    }
    
    /**
     * 转换工具定义为 Gemini 格式
     */
    private ObjectNode toolToGeminiFormat(ToolDefinition tool) {
        ObjectNode node = JsonUtil.getMapper().createObjectNode();
        node.put("name", tool.name());
        node.put("description", tool.description());
        
        // 参数 schema
        ObjectNode parameters = JsonUtil.getMapper().createObjectNode();
        parameters.put("type", "OBJECT");
        
        if (tool.parameters() != null && tool.parameters().properties() != null) {
            ObjectNode properties = JsonUtil.getMapper().createObjectNode();
            for (Map.Entry<String, PropertySchema> entry : tool.parameters().properties().entrySet()) {
                ObjectNode prop = JsonUtil.getMapper().createObjectNode();
                prop.put("type", entry.getValue().type().toUpperCase());
                prop.put("description", entry.getValue().description());
                properties.set(entry.getKey(), prop);
            }
            parameters.set("properties", properties);
            
            if (tool.parameters().required() != null) {
                ArrayNode required = JsonUtil.getMapper().createArrayNode();
                tool.parameters().required().forEach(required::add);
                parameters.set("required", required);
            }
        }
        
        node.set("parameters", parameters);
        return node;
    }
    
    /**
     * 解析 Gemini API 响应
     */
    private AssistantMessage parseGeminiResponse(String responseBody) throws Exception {
        JsonNode json = JsonUtil.parse(responseBody);
        
        JsonNode candidates = json.get("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            return AssistantMessage.text("");
        }
        
        JsonNode content = candidates.get(0).get("content");
        if (content == null) {
            return AssistantMessage.text("");
        }
        
        return parseGeminiContent(content);
    }
    
    /**
     * 解析 Gemini content 对象
     */
    private AssistantMessage parseGeminiContent(JsonNode content) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        
        JsonNode parts = content.get("parts");
        if (parts != null && parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.has("text")) {
                    text.append(part.get("text").asText());
                } else if (part.has("functionCall")) {
                    JsonNode fc = part.get("functionCall");
                    String name = fc.get("name").asText();
                    Map<String, Object> args = new HashMap<>();
                    
                    JsonNode argsNode = fc.get("args");
                    if (argsNode != null) {
                        argsNode.fields().forEachRemaining(entry -> 
                                args.put(entry.getKey(), nodeToValue(entry.getValue())));
                    }
                    
                    toolCalls.add(ToolCall.of(name, args));
                }
            }
        }
        
        return new AssistantMessage(text.toString(), toolCalls.isEmpty() ? null : toolCalls);
    }
    
    /**
     * 解析 Gemini SSE 流
     */
    private void parseGeminiSSEStream(java.io.InputStream inputStream, StreamingHandler handler) {
        StringBuilder contentBuilder = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        
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
                    JsonNode json = JsonUtil.parse(data);
                    JsonNode candidates = json.get("candidates");
                    
                    if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
                        continue;
                    }
                    
                    JsonNode content = candidates.get(0).get("content");
                    if (content == null) {
                        continue;
                    }
                    
                    JsonNode parts = content.get("parts");
                    if (parts != null && parts.isArray()) {
                        for (JsonNode part : parts) {
                            if (part.has("text")) {
                                String text = part.get("text").asText();
                                contentBuilder.append(text);
                                handler.onToken(text);
                            } else if (part.has("functionCall")) {
                                JsonNode fc = part.get("functionCall");
                                String name = fc.get("name").asText();
                                Map<String, Object> args = new HashMap<>();
                                
                                JsonNode argsNode = fc.get("args");
                                if (argsNode != null) {
                                    argsNode.fields().forEachRemaining(entry -> 
                                            args.put(entry.getKey(), nodeToValue(entry.getValue())));
                                }
                                
                                ToolCall tc = ToolCall.of(name, args);
                                toolCalls.add(tc);
                                handler.onToolCall(tc);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse event: {}", data, e);
                }
            }
            
            handler.onComplete(contentBuilder.toString(), toolCalls.isEmpty() ? null : toolCalls);
            
        } catch (Exception e) {
            log.error("Failed to parse Gemini SSE stream", e);
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
    
    // ==================== Builder ====================
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String model = "gemini-1.5-flash";
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
        
        public GeminiAdapter build() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("API key is required");
            }
            return new GeminiAdapter(this);
        }
    }
}
