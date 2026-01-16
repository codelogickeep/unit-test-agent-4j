package com.codelogickeep.agent.ut.framework.tool;

import com.codelogickeep.agent.ut.framework.annotation.P;
import com.codelogickeep.agent.ut.framework.annotation.Tool;
import com.codelogickeep.agent.ut.framework.model.ToolCall;
import com.codelogickeep.agent.ut.framework.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 单元测试
 * 
 * 测试场景：
 * - 工具注册
 * - 工具定义生成
 * - 工具调用执行
 * - 参数类型映射
 * - 错误处理
 */
@DisplayName("ToolRegistry Tests")
class ToolRegistryTest {
    
    private ToolRegistry registry;
    
    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }
    
    // ========== 测试用的工具类 ==========
    
    /**
     * 简单的测试工具类
     */
    public static class SimpleTool {
        
        @Tool("Echo the input message")
        public String echo(@P("Message to echo") String message) {
            return "Echo: " + message;
        }
        
        @Tool("Add two numbers")
        public String add(
                @P("First number") int a,
                @P("Second number") int b) {
            return String.valueOf(a + b);
        }
        
        @Tool("Greet with optional title")
        public String greet(
                @P("Name to greet") String name,
                @P(value = "Title prefix", required = false) String title) {
            if (title != null && !title.isEmpty()) {
                return "Hello, " + title + " " + name;
            }
            return "Hello, " + name;
        }
        
        // 非工具方法
        public String notATool(String input) {
            return input;
        }
    }
    
    /**
     * 支持不同类型参数的工具类
     */
    public static class TypedTool {
        
        @Tool("Test various parameter types")
        public String testTypes(
                @P("String param") String strParam,
                @P("Integer param") int intParam,
                @P("Long param") long longParam,
                @P("Double param") double doubleParam,
                @P("Boolean param") boolean boolParam) {
            return String.format("str=%s, int=%d, long=%d, double=%.2f, bool=%b",
                    strParam, intParam, longParam, doubleParam, boolParam);
        }
    }
    
    /**
     * 会抛出异常的工具类
     */
    public static class FailingTool {
        
        @Tool("This tool always fails")
        public String alwaysFail(@P("Input") String input) {
            throw new RuntimeException("Intentional failure: " + input);
        }
    }
    
    // ========== 工具注册测试 ==========
    
    @Test
    @DisplayName("注册单个工具类")
    void testRegisterSingleTool() {
        registry.register(new SimpleTool());
        
        assertTrue(registry.hasTools());
        assertEquals(3, registry.size()); // echo, add, greet
    }
    
    @Test
    @DisplayName("注册多个工具类 - 数组形式")
    void testRegisterMultipleToolsArray() {
        registry.registerAll(new SimpleTool(), new TypedTool());
        
        assertEquals(4, registry.size()); // 3 + 1
    }
    
    @Test
    @DisplayName("注册多个工具类 - 列表形式")
    void testRegisterMultipleToolsList() {
        registry.registerAll(List.of(new SimpleTool(), new TypedTool()));
        
        assertEquals(4, registry.size());
    }
    
    @Test
    @DisplayName("不应注册非工具方法")
    void testNonToolMethodNotRegistered() {
        registry.register(new SimpleTool());
        
        // notATool 方法不应该被注册
        String result = registry.invoke("notATool", Map.of("input", "test"));
        assertTrue(result.contains("Unknown tool"));
    }
    
    // ========== 工具定义测试 ==========
    
    @Test
    @DisplayName("获取工具定义列表")
    void testGetDefinitions() {
        registry.register(new SimpleTool());
        
        List<ToolDefinition> definitions = registry.getDefinitions();
        
        assertEquals(3, definitions.size());
        
        // 验证定义内容
        boolean hasEcho = definitions.stream()
                .anyMatch(d -> d.name().equals("echo") && d.description().equals("Echo the input message"));
        assertTrue(hasEcho);
    }
    
    @Test
    @DisplayName("工具定义应包含参数schema")
    void testDefinitionHasParameterSchema() {
        registry.register(new SimpleTool());
        
        ToolDefinition addDef = registry.getDefinitions().stream()
                .filter(d -> d.name().equals("add"))
                .findFirst()
                .orElseThrow();
        
        assertNotNull(addDef.parameters());
        assertEquals("object", addDef.parameters().type());
        assertNotNull(addDef.parameters().properties());
        assertTrue(addDef.parameters().properties().containsKey("a"));
        assertTrue(addDef.parameters().properties().containsKey("b"));
    }
    
    @Test
    @DisplayName("必填参数应在required列表中")
    void testRequiredParameters() {
        registry.register(new SimpleTool());
        
        ToolDefinition echoDef = registry.getDefinitions().stream()
                .filter(d -> d.name().equals("echo"))
                .findFirst()
                .orElseThrow();
        
        assertTrue(echoDef.parameters().required().contains("message"));
    }
    
    @Test
    @DisplayName("可选参数不应在required列表中")
    void testOptionalParameterNotRequired() {
        registry.register(new SimpleTool());
        
        ToolDefinition greetDef = registry.getDefinitions().stream()
                .filter(d -> d.name().equals("greet"))
                .findFirst()
                .orElseThrow();
        
        // name 应该是必填
        assertTrue(greetDef.parameters().required().contains("name"));
        // title 是可选的，不应该在 required 列表中
        assertFalse(greetDef.parameters().required().contains("title"));
    }
    
    // ========== 工具调用测试 ==========
    
    @Test
    @DisplayName("调用简单工具")
    void testInvokeSimpleTool() {
        registry.register(new SimpleTool());
        
        String result = registry.invoke("echo", Map.of("message", "Hello"));
        
        assertEquals("Echo: Hello", result);
    }
    
    @Test
    @DisplayName("调用带多个参数的工具")
    void testInvokeToolWithMultipleParams() {
        registry.register(new SimpleTool());
        
        String result = registry.invoke("add", Map.of("a", 5, "b", 3));
        
        assertEquals("8", result);
    }
    
    @Test
    @DisplayName("通过ToolCall对象调用工具")
    void testInvokeViaToolCall() {
        registry.register(new SimpleTool());
        
        ToolCall toolCall = new ToolCall("call_1", "echo", Map.of("message", "Test"));
        String result = registry.invoke(toolCall);
        
        assertEquals("Echo: Test", result);
    }
    
    @Test
    @DisplayName("调用不存在的工具应返回错误")
    void testInvokeUnknownTool() {
        registry.register(new SimpleTool());
        
        String result = registry.invoke("nonExistent", Map.of());
        
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("Unknown tool"));
    }
    
    @Test
    @DisplayName("工具执行异常应被捕获并返回错误信息")
    void testToolExecutionException() {
        registry.register(new FailingTool());
        
        String result = registry.invoke("alwaysFail", Map.of("input", "test"));
        
        assertTrue(result.contains("Error") || result.contains("Intentional failure"));
    }
    
    // ========== 类型映射测试 ==========
    
    @Test
    @DisplayName("支持多种参数类型")
    void testVariousParameterTypes() {
        registry.register(new TypedTool());
        
        String result = registry.invoke("testTypes", Map.of(
                "strParam", "hello",
                "intParam", 42,
                "longParam", 1234567890L,
                "doubleParam", 3.14,
                "boolParam", true
        ));
        
        assertTrue(result.contains("str=hello"));
        assertTrue(result.contains("int=42"));
        assertTrue(result.contains("bool=true"));
    }
    
    @Test
    @DisplayName("参数类型映射到JSON类型")
    void testTypeMapping() {
        registry.register(new TypedTool());
        
        ToolDefinition def = registry.getDefinitions().get(0);
        
        assertEquals("string", def.parameters().properties().get("strParam").type());
        assertEquals("integer", def.parameters().properties().get("intParam").type());
        assertEquals("integer", def.parameters().properties().get("longParam").type());
        assertEquals("number", def.parameters().properties().get("doubleParam").type());
        assertEquals("boolean", def.parameters().properties().get("boolParam").type());
    }
    
    // ========== 边界条件测试 ==========
    
    @Test
    @DisplayName("空注册表hasTools返回false")
    void testEmptyRegistryHasNoTools() {
        assertFalse(registry.hasTools());
        assertEquals(0, registry.size());
    }
    
    @Test
    @DisplayName("获取空注册表的定义列表")
    void testGetDefinitionsFromEmptyRegistry() {
        List<ToolDefinition> definitions = registry.getDefinitions();
        
        assertNotNull(definitions);
        assertTrue(definitions.isEmpty());
    }
    
    @Test
    @DisplayName("调用工具时参数为空Map")
    void testInvokeWithEmptyArgs() {
        registry.register(new SimpleTool());
        
        // greet 方法的 title 参数是可选的
        // 但 name 是必填的，空Map会导致null
        String result = registry.invoke("greet", Map.of("name", "World"));
        
        assertEquals("Hello, World", result);
    }
    
    @Test
    @DisplayName("参数值为null的处理")
    void testInvokeWithNullArgValue() {
        registry.register(new SimpleTool());
        
        java.util.HashMap<String, Object> args = new java.util.HashMap<>();
        args.put("name", "Alice");
        args.put("title", null);
        
        String result = registry.invoke("greet", args);
        
        // null title 应该被处理
        assertEquals("Hello, Alice", result);
    }
}
