package com.codelogickeep.agent.ut.framework.tool;

import com.codelogickeep.agent.ut.framework.model.ToolCall;
import com.codelogickeep.agent.ut.framework.model.ToolDefinition;
import com.codelogickeep.agent.ut.framework.model.ToolDefinition.ParameterSchema;
import com.codelogickeep.agent.ut.framework.model.ToolDefinition.PropertySchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * 工具注册表 - 管理所有可被 LLM 调用的工具
 * 
 * 支持两种注解：
 * 1. 自定义注解: com.codelogickeep.agent.ut.framework.annotation.Tool / @P
 * 2. LangChain4j 注解: dev.langchain4j.agent.tool.Tool / @P (兼容模式)
 */
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    
    private final Map<String, ToolExecutor> tools = new LinkedHashMap<>();
    private final List<ToolDefinition> definitions = new ArrayList<>();
    
    /**
     * 注册工具实例
     * 
     * @param toolInstance 包含 @Tool 方法的对象
     */
    public void register(Object toolInstance) {
        Class<?> clazz = toolInstance.getClass();
        
        for (Method method : clazz.getDeclaredMethods()) {
            String toolDescription = getToolDescription(method);
            if (toolDescription == null) {
                continue;  // 不是工具方法
            }
            
            String toolName = method.getName();
            
            // 解析参数
            ToolExecutor.ToolParameter[] params = parseParameters(method);
            
            // 创建执行器
            ToolExecutor executor = new ToolExecutor(toolInstance, method, params);
            
            // 创建定义
            ToolDefinition definition = createDefinition(toolName, toolDescription, params);
            
            // 注册
            tools.put(toolName, executor);
            definitions.add(definition);
            
            log.debug("Registered tool: {} - {}", toolName, toolDescription);
        }
        
        log.info("Registered {} tools from {}", 
                tools.size() - definitions.size() + countToolsInClass(clazz),
                clazz.getSimpleName());
    }
    
    private int countToolsInClass(Class<?> clazz) {
        int count = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (getToolDescription(method) != null) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * 注册多个工具实例
     */
    public void registerAll(Object... toolInstances) {
        for (Object tool : toolInstances) {
            register(tool);
        }
    }
    
    /**
     * 注册多个工具实例
     */
    public void registerAll(List<Object> toolInstances) {
        for (Object tool : toolInstances) {
            register(tool);
        }
    }
    
    /**
     * 执行工具调用
     */
    public String invoke(ToolCall toolCall) {
        return invoke(toolCall.name(), toolCall.arguments());
    }
    
    /**
     * 执行工具调用
     */
    public String invoke(String name, Map<String, Object> arguments) {
        ToolExecutor executor = tools.get(name);
        if (executor == null) {
            log.warn("Unknown tool: {}", name);
            return "Error: Unknown tool: " + name;
        }
        
        log.info("Executing tool: {} with args: {}", name, arguments);
        long start = System.currentTimeMillis();
        
        String result = executor.execute(arguments);
        
        long duration = System.currentTimeMillis() - start;
        log.debug("Tool {} completed in {}ms, result length: {}", 
                name, duration, result.length());
        
        return result;
    }
    
    /**
     * 获取所有工具定义
     */
    public List<ToolDefinition> getDefinitions() {
        return Collections.unmodifiableList(definitions);
    }
    
    /**
     * 获取工具数量
     */
    public int size() {
        return tools.size();
    }
    
    /**
     * 检查工具是否存在
     */
    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /**
     * 清空所有工具
     */
    public void clear() {
        tools.clear();
        definitions.clear();
        log.debug("Cleared all tools");
    }

    /**
     * 获取所有工具名称
     */
    public List<String> getToolNames() {
        return new ArrayList<>(tools.keySet());
    }

    /**
     * 获取工具描述（支持多种注解）
     */
    private String getToolDescription(Method method) {
        // 1. 尝试自定义注解
        com.codelogickeep.agent.ut.framework.annotation.Tool customTool = 
                method.getAnnotation(com.codelogickeep.agent.ut.framework.annotation.Tool.class);
        if (customTool != null) {
            return customTool.value();
        }
        
        // 2. 尝试 LangChain4j 注解（通过反射，避免硬依赖）
        try {
            for (Annotation annotation : method.getAnnotations()) {
                Class<?> annotationType = annotation.annotationType();
                if (annotationType.getName().equals("dev.langchain4j.agent.tool.Tool")) {
                    Method valueMethod = annotationType.getMethod("value");
                    Object value = valueMethod.invoke(annotation);
                    if (value instanceof String[] arr && arr.length > 0) {
                        return arr[0];
                    }
                    if (value instanceof String str) {
                        return str;
                    }
                }
            }
        } catch (Exception e) {
            // LangChain4j 注解不可用，忽略
        }
        
        return null;
    }
    
    /**
     * 解析方法参数
     */
    private ToolExecutor.ToolParameter[] parseParameters(Method method) {
        Parameter[] params = method.getParameters();
        ToolExecutor.ToolParameter[] result = new ToolExecutor.ToolParameter[params.length];
        
        for (int i = 0; i < params.length; i++) {
            Parameter param = params[i];
            String name = param.getName();
            String description = getParameterDescription(param);
            boolean required = isParameterRequired(param);
            
            result[i] = new ToolExecutor.ToolParameter(name, description, param.getType(), required);
        }
        
        return result;
    }
    
    /**
     * 获取参数描述（支持多种注解）
     */
    private String getParameterDescription(Parameter param) {
        // 1. 尝试自定义注解
        com.codelogickeep.agent.ut.framework.annotation.P customP = 
                param.getAnnotation(com.codelogickeep.agent.ut.framework.annotation.P.class);
        if (customP != null) {
            return customP.value();
        }
        
        // 2. 尝试 LangChain4j 注解
        try {
            for (Annotation annotation : param.getAnnotations()) {
                Class<?> annotationType = annotation.annotationType();
                if (annotationType.getName().equals("dev.langchain4j.agent.tool.P")) {
                    Method valueMethod = annotationType.getMethod("value");
                    return (String) valueMethod.invoke(annotation);
                }
            }
        } catch (Exception e) {
            // LangChain4j 注解不可用
        }
        
        return param.getName();
    }
    
    /**
     * 检查参数是否必需
     */
    private boolean isParameterRequired(Parameter param) {
        // 1. 尝试自定义注解
        com.codelogickeep.agent.ut.framework.annotation.P customP = 
                param.getAnnotation(com.codelogickeep.agent.ut.framework.annotation.P.class);
        if (customP != null) {
            return customP.required();
        }
        
        // 默认为必需
        return true;
    }
    
    /**
     * 创建工具定义
     */
    private ToolDefinition createDefinition(String name, String description, 
                                            ToolExecutor.ToolParameter[] params) {
        Map<String, PropertySchema> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        
        for (ToolExecutor.ToolParameter param : params) {
            String type = mapJavaTypeToJsonType(param.type());
            properties.put(param.name(), new PropertySchema(type, param.description()));
            
            if (param.required()) {
                required.add(param.name());
            }
        }
        
        ParameterSchema paramSchema = new ParameterSchema("object", properties, required);
        return new ToolDefinition(name, description, paramSchema);
    }
    
    /**
     * 映射 Java 类型到 JSON Schema 类型
     */
    private String mapJavaTypeToJsonType(Class<?> type) {
        if (type == String.class) {
            return "string";
        }
        if (type == int.class || type == Integer.class || 
            type == long.class || type == Long.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class || 
            type == float.class || type == Float.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        // 默认为字符串
        return "string";
    }
}
