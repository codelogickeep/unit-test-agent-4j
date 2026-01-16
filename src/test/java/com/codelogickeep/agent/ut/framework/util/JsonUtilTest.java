package com.codelogickeep.agent.ut.framework.util;

import com.codelogickeep.agent.ut.framework.model.*;
import com.codelogickeep.agent.ut.framework.model.ToolDefinition.ParameterSchema;
import com.codelogickeep.agent.ut.framework.model.ToolDefinition.PropertySchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonUtil 单元测试
 * 
 * 测试场景：
 * - 消息转JSON
 * - 工具定义转JSON
 * - 响应解析
 * - 请求构建
 */
@DisplayName("JsonUtil Tests")
class JsonUtilTest {
    
    // ========== 消息转JSON测试 ==========
    
    @Test
    @DisplayName("系统消息转JSON")
    void testSystemMessageToJson() {
        SystemMessage msg = new SystemMessage("You are a helpful assistant.");
        ObjectNode json = JsonUtil.messageToJson(msg);
        
        assertEquals("system", json.get("role").asText());
        assertEquals("You are a helpful assistant.", json.get("content").asText());
    }
    
    @Test
    @DisplayName("用户消息转JSON")
    void testUserMessageToJson() {
        UserMessage msg = new UserMessage("Hello, how are you?");
        ObjectNode json = JsonUtil.messageToJson(msg);
        
        assertEquals("user", json.get("role").asText());
        assertEquals("Hello, how are you?", json.get("content").asText());
    }
    
    @Test
    @DisplayName("助手消息转JSON - 纯文本")
    void testAssistantMessageTextToJson() {
        AssistantMessage msg = AssistantMessage.text("I'm doing well, thank you!");
        ObjectNode json = JsonUtil.messageToJson(msg);
        
        assertEquals("assistant", json.get("role").asText());
        assertEquals("I'm doing well, thank you!", json.get("content").asText());
        assertFalse(json.has("tool_calls"));
    }
    
    @Test
    @DisplayName("助手消息转JSON - 带工具调用")
    void testAssistantMessageWithToolCallsToJson() {
        List<ToolCall> toolCalls = List.of(
            new ToolCall("call_123", "readFile", Map.of("path", "test.java"))
        );
        AssistantMessage msg = new AssistantMessage("Let me read that file.", toolCalls);
        ObjectNode json = JsonUtil.messageToJson(msg);
        
        assertEquals("assistant", json.get("role").asText());
        assertEquals("Let me read that file.", json.get("content").asText());
        assertTrue(json.has("tool_calls"));
        
        JsonNode toolCallsNode = json.get("tool_calls");
        assertTrue(toolCallsNode.isArray());
        assertEquals(1, toolCallsNode.size());
        
        JsonNode tcNode = toolCallsNode.get(0);
        assertEquals("call_123", tcNode.get("id").asText());
        assertEquals("function", tcNode.get("type").asText());
        assertEquals("readFile", tcNode.get("function").get("name").asText());
    }
    
    @Test
    @DisplayName("助手消息转JSON - content为null时应设为空字符串（智谱AI兼容）")
    void testAssistantMessageNullContentToJson() {
        List<ToolCall> toolCalls = List.of(
            new ToolCall("call_1", "test", Map.of())
        );
        AssistantMessage msg = new AssistantMessage(null, toolCalls);
        ObjectNode json = JsonUtil.messageToJson(msg);
        
        // 关键：content 字段应该存在且为空字符串
        assertTrue(json.has("content"));
        assertEquals("", json.get("content").asText());
    }
    
    @Test
    @DisplayName("工具消息转JSON")
    void testToolMessageToJson() {
        ToolMessage msg = new ToolMessage("call_123", "readFile", "File content here");
        ObjectNode json = JsonUtil.messageToJson(msg);
        
        assertEquals("tool", json.get("role").asText());
        assertEquals("call_123", json.get("tool_call_id").asText());
        assertEquals("File content here", json.get("content").asText());
    }
    
    @Test
    @DisplayName("工具消息转JSON - 超长内容应被截断")
    void testToolMessageLongContentTruncated() {
        String longContent = "x".repeat(60000); // 超过50000
        ToolMessage msg = new ToolMessage("call_1", "test", longContent);
        ObjectNode json = JsonUtil.messageToJson(msg);
        
        String content = json.get("content").asText();
        assertTrue(content.length() < 60000);
        assertTrue(content.endsWith("(truncated)"));
    }
    
    @Test
    @DisplayName("工具消息转JSON - null内容应转为空字符串")
    void testToolMessageNullContent() {
        ToolMessage msg = new ToolMessage("call_1", "test", null);
        ObjectNode json = JsonUtil.messageToJson(msg);
        
        assertEquals("", json.get("content").asText());
    }
    
    @Test
    @DisplayName("消息列表转JSON数组")
    void testMessagesToJson() {
        List<Message> messages = List.of(
            new SystemMessage("System prompt"),
            new UserMessage("Hello"),
            AssistantMessage.text("Hi there")
        );
        
        ArrayNode json = JsonUtil.messagesToJson(messages);
        
        assertEquals(3, json.size());
        assertEquals("system", json.get(0).get("role").asText());
        assertEquals("user", json.get(1).get("role").asText());
        assertEquals("assistant", json.get(2).get("role").asText());
    }
    
    // ========== 工具定义转JSON测试 ==========
    
    @Test
    @DisplayName("工具定义转JSON")
    void testToolDefinitionToJson() {
        Map<String, PropertySchema> props = new LinkedHashMap<>();
        props.put("path", new PropertySchema("string", "File path to read"));
        props.put("encoding", new PropertySchema("string", "File encoding"));
        
        ParameterSchema params = new ParameterSchema("object", props, List.of("path"));
        ToolDefinition tool = new ToolDefinition("readFile", "Read a file from disk", params);
        
        ObjectNode json = JsonUtil.toolToJson(tool);
        
        assertEquals("function", json.get("type").asText());
        
        JsonNode functionNode = json.get("function");
        assertEquals("readFile", functionNode.get("name").asText());
        assertEquals("Read a file from disk", functionNode.get("description").asText());
        
        JsonNode paramsNode = functionNode.get("parameters");
        assertEquals("object", paramsNode.get("type").asText());
        assertTrue(paramsNode.has("properties"));
        assertTrue(paramsNode.get("properties").has("path"));
        assertTrue(paramsNode.get("properties").has("encoding"));
        
        // required 应只包含 path
        assertTrue(paramsNode.has("required"));
        assertEquals(1, paramsNode.get("required").size());
        assertEquals("path", paramsNode.get("required").get(0).asText());
    }
    
    @Test
    @DisplayName("工具定义列表转JSON数组")
    void testToolsToJson() {
        ToolDefinition tool1 = new ToolDefinition("tool1", "First tool", 
            new ParameterSchema("object", Map.of(), List.of()));
        ToolDefinition tool2 = new ToolDefinition("tool2", "Second tool",
            new ParameterSchema("object", Map.of(), List.of()));
        
        ArrayNode json = JsonUtil.toolsToJson(List.of(tool1, tool2));
        
        assertEquals(2, json.size());
        assertEquals("tool1", json.get(0).get("function").get("name").asText());
        assertEquals("tool2", json.get(1).get("function").get("name").asText());
    }
    
    @Test
    @DisplayName("空参数的工具定义")
    void testToolDefinitionWithNullParams() {
        ToolDefinition tool = new ToolDefinition("simple", "Simple tool", null);
        ObjectNode json = JsonUtil.toolToJson(tool);
        
        // 应该不会抛出异常
        assertNotNull(json);
        assertEquals("simple", json.get("function").get("name").asText());
    }
    
    // ========== 响应解析测试 ==========
    
    @Test
    @DisplayName("解析助手消息 - 纯文本")
    void testParseAssistantMessageText() throws JsonProcessingException {
        String json = """
            {
                "message": {
                    "role": "assistant",
                    "content": "Hello, I can help you."
                }
            }
            """;
        JsonNode node = JsonUtil.parse(json);
        AssistantMessage msg = JsonUtil.parseAssistantMessage(node);
        
        assertEquals("Hello, I can help you.", msg.content());
        assertFalse(msg.hasToolCalls());
    }
    
    @Test
    @DisplayName("解析助手消息 - 带工具调用")
    void testParseAssistantMessageWithToolCalls() throws JsonProcessingException {
        String json = """
            {
                "message": {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [
                        {
                            "id": "call_abc123",
                            "type": "function",
                            "function": {
                                "name": "readFile",
                                "arguments": "{\\"path\\": \\"test.java\\"}"
                            }
                        }
                    ]
                }
            }
            """;
        JsonNode node = JsonUtil.parse(json);
        AssistantMessage msg = JsonUtil.parseAssistantMessage(node);
        
        assertTrue(msg.hasToolCalls());
        assertEquals(1, msg.toolCalls().size());
        
        ToolCall tc = msg.toolCalls().get(0);
        assertEquals("call_abc123", tc.id());
        assertEquals("readFile", tc.name());
        assertEquals("test.java", tc.arguments().get("path"));
    }
    
    @Test
    @DisplayName("解析助手消息 - delta格式（流式响应）")
    void testParseAssistantMessageDelta() throws JsonProcessingException {
        String json = """
            {
                "delta": {
                    "role": "assistant",
                    "content": "Partial response"
                }
            }
            """;
        JsonNode node = JsonUtil.parse(json);
        AssistantMessage msg = JsonUtil.parseAssistantMessage(node);
        
        assertEquals("Partial response", msg.content());
    }
    
    @Test
    @DisplayName("解析助手消息 - 空节点")
    void testParseAssistantMessageEmpty() throws JsonProcessingException {
        String json = "{}";
        JsonNode node = JsonUtil.parse(json);
        AssistantMessage msg = JsonUtil.parseAssistantMessage(node);
        
        assertEquals("", msg.content());
        assertFalse(msg.hasToolCalls());
    }
    
    @Test
    @DisplayName("解析工具调用 - null输入")
    void testParseToolCallsNull() {
        List<ToolCall> result = JsonUtil.parseToolCalls(null);
        assertNull(result);
    }
    
    @Test
    @DisplayName("解析工具调用 - 空数组")
    void testParseToolCallsEmptyArray() throws JsonProcessingException {
        JsonNode node = JsonUtil.parse("[]");
        List<ToolCall> result = JsonUtil.parseToolCalls(node);
        assertNull(result);
    }
    
    @Test
    @DisplayName("解析工具调用 - 多个调用")
    void testParseMultipleToolCalls() throws JsonProcessingException {
        String json = """
            [
                {
                    "id": "call_1",
                    "function": {
                        "name": "readFile",
                        "arguments": "{\\"path\\": \\"a.java\\"}"
                    }
                },
                {
                    "id": "call_2",
                    "function": {
                        "name": "writeFile",
                        "arguments": "{\\"path\\": \\"b.java\\", \\"content\\": \\"test\\"}"
                    }
                }
            ]
            """;
        JsonNode node = JsonUtil.parse(json);
        List<ToolCall> calls = JsonUtil.parseToolCalls(node);
        
        assertNotNull(calls);
        assertEquals(2, calls.size());
        assertEquals("readFile", calls.get(0).name());
        assertEquals("writeFile", calls.get(1).name());
    }
    
    // ========== 请求构建测试 ==========
    
    @Test
    @DisplayName("构建聊天请求 - 基础")
    void testBuildChatRequestBasic() throws JsonProcessingException {
        List<Message> messages = List.of(
            new UserMessage("Hello")
        );
        
        String request = JsonUtil.buildChatRequest("gpt-4", messages, null, 0.0, false);
        JsonNode node = JsonUtil.parse(request);
        
        assertEquals("gpt-4", node.get("model").asText());
        assertEquals(1, node.get("messages").size());
        assertEquals(0.0, node.get("temperature").asDouble());
        assertFalse(node.has("stream"));
    }
    
    @Test
    @DisplayName("构建聊天请求 - 带工具")
    void testBuildChatRequestWithTools() throws JsonProcessingException {
        List<Message> messages = List.of(new UserMessage("Read file"));
        List<ToolDefinition> tools = List.of(
            new ToolDefinition("readFile", "Read a file",
                new ParameterSchema("object", Map.of(), List.of()))
        );
        
        String request = JsonUtil.buildChatRequest("gpt-4", messages, tools, 0.5, false);
        JsonNode node = JsonUtil.parse(request);
        
        assertTrue(node.has("tools"));
        assertEquals(1, node.get("tools").size());
        assertEquals("auto", node.get("tool_choice").asText());
    }
    
    @Test
    @DisplayName("构建聊天请求 - 流式")
    void testBuildChatRequestStream() throws JsonProcessingException {
        List<Message> messages = List.of(new UserMessage("Hello"));
        
        String request = JsonUtil.buildChatRequest("gpt-4", messages, null, null, true);
        JsonNode node = JsonUtil.parse(request);
        
        assertTrue(node.get("stream").asBoolean());
    }
    
    @Test
    @DisplayName("构建聊天请求 - 无温度参数")
    void testBuildChatRequestNoTemperature() throws JsonProcessingException {
        List<Message> messages = List.of(new UserMessage("Hello"));
        
        String request = JsonUtil.buildChatRequest("gpt-4", messages, null, null, false);
        JsonNode node = JsonUtil.parse(request);
        
        assertFalse(node.has("temperature"));
    }
    
    // ========== 工具方法测试 ==========
    
    @Test
    @DisplayName("解析JSON字符串")
    void testParse() throws JsonProcessingException {
        String json = "{\"key\": \"value\", \"num\": 42}";
        JsonNode node = JsonUtil.parse(json);
        
        assertEquals("value", node.get("key").asText());
        assertEquals(42, node.get("num").asInt());
    }
    
    @Test
    @DisplayName("解析无效JSON应抛出异常")
    void testParseInvalidJson() {
        assertThrows(JsonProcessingException.class, () -> {
            JsonUtil.parse("not valid json");
        });
    }
    
    @Test
    @DisplayName("转换对象为JSON字符串")
    void testToJson() throws JsonProcessingException {
        Map<String, Object> obj = Map.of("key", "value", "num", 42);
        String json = JsonUtil.toJson(obj);
        
        assertTrue(json.contains("\"key\""));
        assertTrue(json.contains("\"value\""));
        assertTrue(json.contains("42"));
    }
    
    @Test
    @DisplayName("获取ObjectMapper实例")
    void testGetMapper() {
        assertNotNull(JsonUtil.getMapper());
    }
    
    // ========== 参数类型解析测试 ==========
    
    @Test
    @DisplayName("解析工具调用参数 - 各种类型")
    void testParseToolCallArgumentTypes() throws JsonProcessingException {
        // 包含 null 值的参数会被跳过（因为 ToolCall 使用 Map.copyOf() 不允许 null）
        String json = """
            [
                {
                    "id": "call_1",
                    "function": {
                        "name": "test",
                        "arguments": "{\\"strVal\\": \\"text\\", \\"intVal\\": 42, \\"boolVal\\": true, \\"floatVal\\": 3.14, \\"nullVal\\": null}"
                    }
                }
            ]
            """;
        JsonNode node = JsonUtil.parse(json);
        List<ToolCall> calls = JsonUtil.parseToolCalls(node);
        
        assertNotNull(calls);
        Map<String, Object> args = calls.get(0).arguments();
        
        assertEquals("text", args.get("strVal"));
        assertEquals(42, args.get("intVal"));
        assertEquals(true, args.get("boolVal"));
        assertEquals(3.14, (Double) args.get("floatVal"), 0.001);
        // null 值会被跳过，所以键不存在
        assertFalse(args.containsKey("nullVal"));
    }
}
