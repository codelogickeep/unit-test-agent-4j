package com.codelogickeep.agent.ut.framework.model;

import java.util.List;
import java.util.Map;

/**
 * 工具定义 - 描述一个可被 LLM 调用的工具
 */
public record ToolDefinition(
        String name,
        String description,
        ParameterSchema parameters
) {
    
    /**
     * 参数 Schema 定义
     */
    public record ParameterSchema(
            String type,
            Map<String, PropertySchema> properties,
            List<String> required
    ) {
        public static ParameterSchema empty() {
            return new ParameterSchema("object", Map.of(), List.of());
        }
    }
    
    /**
     * 属性 Schema 定义
     */
    public record PropertySchema(
            String type,
            String description
    ) {
        public static PropertySchema string(String description) {
            return new PropertySchema("string", description);
        }
        
        public static PropertySchema integer(String description) {
            return new PropertySchema("integer", description);
        }
        
        public static PropertySchema number(String description) {
            return new PropertySchema("number", description);
        }
        
        public static PropertySchema bool(String description) {
            return new PropertySchema("boolean", description);
        }
    }
    
    /**
     * 创建工具定义
     */
    public static ToolDefinition of(String name, String description, ParameterSchema parameters) {
        return new ToolDefinition(name, description, parameters);
    }
    
    /**
     * 创建无参数工具定义
     */
    public static ToolDefinition noParams(String name, String description) {
        return new ToolDefinition(name, description, ParameterSchema.empty());
    }
}
