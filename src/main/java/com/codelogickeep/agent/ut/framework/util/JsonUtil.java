package com.codelogickeep.agent.ut.framework.util;

import com.codelogickeep.agent.ut.framework.model.*;
import com.codelogickeep.agent.ut.framework.model.ToolDefinition.ParameterSchema;
import com.codelogickeep.agent.ut.framework.model.ToolDefinition.PropertySchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * JSON 工具类 - 处理 LLM API 的 JSON 序列化/反序列化
 */
public class JsonUtil {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 将消息列表转换为 OpenAI 格式的 JSON 数组
     */
    public static ArrayNode messagesToJson(List<Message> messages) {
        ArrayNode array = mapper.createArrayNode();

        for (Message msg : messages) {
            array.add(messageToJson(msg));
        }

        return array;
    }

    /**
     * 将单个消息转换为 JSON
     */
    public static ObjectNode messageToJson(Message message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.role());

        switch (message) {
            case SystemMessage sys -> {
                node.put("content", sys.content());
            }
            case UserMessage user -> {
                node.put("content", user.content());
            }
            case AssistantMessage assistant -> {
                // 关键：始终包含 content 字段，解决智谱 AI 1214 问题
                // 即使有 tool_calls，content 也必须存在（可以为空字符串）
                node.put("content", assistant.content() != null ? assistant.content() : "");

                if (assistant.hasToolCalls()) {
                    ArrayNode toolCallsNode = mapper.createArrayNode();
                    int index = 0;
                    for (ToolCall tc : assistant.toolCalls()) {
                        ObjectNode tcNode = mapper.createObjectNode();
                        // 确保 id 存在，智谱 AI 可能依赖此 id 来匹配 tool 响应
                        String toolCallId = tc.id();
                        if (toolCallId == null || toolCallId.isEmpty()) {
                            toolCallId = "call_" + System.currentTimeMillis() + "_" + index;
                        }
                        tcNode.put("id", toolCallId);
                        tcNode.put("type", "function");
                        // 智谱 AI 可能需要 index 字段
                        tcNode.put("index", index);

                        ObjectNode functionNode = mapper.createObjectNode();
                        functionNode.put("name", tc.name());
                        try {
                            functionNode.put("arguments", mapper.writeValueAsString(tc.arguments()));
                        } catch (JsonProcessingException e) {
                            functionNode.put("arguments", "{}");
                        }
                        tcNode.set("function", functionNode);

                        toolCallsNode.add(tcNode);
                        index++;
                    }
                    node.set("tool_calls", toolCallsNode);
                }
            }
            case ToolMessage tool -> {
                // 智谱 AI 要求 tool 消息不包含 name 字段，只需要 tool_call_id 和 content
                node.put("tool_call_id", tool.toolCallId());
                // 确保 content 不为 null
                String toolContent = tool.content();
                if (toolContent == null) {
                    toolContent = "";
                }
                // 智谱 AI 可能对 content 长度有限制，截断过长内容
                if (toolContent.length() > 50000) {
                    toolContent = toolContent.substring(0, 50000) + "\n... (truncated)";
                }
                node.put("content", toolContent);
            }
        }

        return node;
    }

    /**
     * 将工具定义列表转换为 OpenAI 格式的 JSON 数组
     */
    public static ArrayNode toolsToJson(List<ToolDefinition> tools) {
        ArrayNode array = mapper.createArrayNode();

        for (ToolDefinition tool : tools) {
            array.add(toolToJson(tool));
        }

        return array;
    }

    /**
     * 将单个工具定义转换为 JSON
     */
    public static ObjectNode toolToJson(ToolDefinition tool) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "function");

        ObjectNode functionNode = mapper.createObjectNode();
        functionNode.put("name", tool.name());
        functionNode.put("description", tool.description());

        // 参数 schema
        ObjectNode paramsNode = mapper.createObjectNode();
        paramsNode.put("type", "object");

        if (tool.parameters() != null && tool.parameters().properties() != null) {
            ObjectNode propsNode = mapper.createObjectNode();
            for (Map.Entry<String, PropertySchema> entry : tool.parameters().properties().entrySet()) {
                ObjectNode propNode = mapper.createObjectNode();
                propNode.put("type", entry.getValue().type());
                propNode.put("description", entry.getValue().description());
                propsNode.set(entry.getKey(), propNode);
            }
            paramsNode.set("properties", propsNode);

            if (tool.parameters().required() != null && !tool.parameters().required().isEmpty()) {
                ArrayNode requiredNode = mapper.createArrayNode();
                tool.parameters().required().forEach(requiredNode::add);
                paramsNode.set("required", requiredNode);
            }
        }

        functionNode.set("parameters", paramsNode);
        node.set("function", functionNode);

        return node;
    }

    /**
     * 从 OpenAI 响应 JSON 解析 AssistantMessage
     */
    public static AssistantMessage parseAssistantMessage(JsonNode choiceNode) {
        JsonNode messageNode = choiceNode.get("message");
        if (messageNode == null) {
            messageNode = choiceNode.get("delta"); // 流式响应
        }

        if (messageNode == null) {
            return AssistantMessage.text("");
        }

        String content = messageNode.has("content") && !messageNode.get("content").isNull()
                ? messageNode.get("content").asText()
                : "";

        List<ToolCall> toolCalls = null;
        if (messageNode.has("tool_calls")) {
            toolCalls = parseToolCalls(messageNode.get("tool_calls"));
        }

        return new AssistantMessage(content, toolCalls);
    }

    /**
     * 解析工具调用列表
     */
    public static List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return null;
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode tcNode : toolCallsNode) {
            String id = tcNode.has("id") ? tcNode.get("id").asText() : null;

            JsonNode functionNode = tcNode.get("function");
            if (functionNode == null) {
                continue;
            }

            String name = functionNode.get("name").asText();
            Map<String, Object> arguments = new HashMap<>();

            if (functionNode.has("arguments")) {
                String argsStr = functionNode.get("arguments").asText();
                try {
                    JsonNode argsNode = mapper.readTree(argsStr);
                    Iterator<Map.Entry<String, JsonNode>> fields = argsNode.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        Object value = nodeToValue(field.getValue());
                        // 跳过 null 值，因为 ToolCall 使用 Map.copyOf() 不允许 null
                        if (value != null) {
                            arguments.put(field.getKey(), value);
                        }
                    }
                } catch (JsonProcessingException e) {
                    // 解析失败，保持空 arguments
                }
            }

            toolCalls.add(new ToolCall(id, name, arguments));
        }

        return toolCalls.isEmpty() ? null : toolCalls;
    }

    /**
     * 将 JsonNode 转换为 Java 值
     */
    private static Object nodeToValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isLong()) {
            return node.asLong();
        }
        if (node.isDouble() || node.isFloat()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNull()) {
            return null;
        }
        // 复杂类型返回字符串
        return node.toString();
    }

    /**
     * 构建 OpenAI Chat Completion 请求体
     */
    public static String buildChatRequest(String model, List<Message> messages,
            List<ToolDefinition> tools,
            Double temperature, boolean stream) {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", model);
        request.set("messages", messagesToJson(messages));

        if (temperature != null) {
            request.put("temperature", temperature);
        }

        if (tools != null && !tools.isEmpty()) {
            request.set("tools", toolsToJson(tools));
            request.put("tool_choice", "auto");
        }

        if (stream) {
            request.put("stream", true);
        }

        try {
            return mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build request JSON", e);
        }
    }

    /**
     * 解析 JSON 字符串
     */
    public static JsonNode parse(String json) throws JsonProcessingException {
        return mapper.readTree(json);
    }

    /**
     * 转换为 JSON 字符串
     */
    public static String toJson(Object obj) throws JsonProcessingException {
        return mapper.writeValueAsString(obj);
    }

    /**
     * 获取 ObjectMapper 实例
     */
    public static ObjectMapper getMapper() {
        return mapper;
    }
}
