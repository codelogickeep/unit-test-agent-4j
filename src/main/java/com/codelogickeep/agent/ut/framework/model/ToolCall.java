package com.codelogickeep.agent.ut.framework.model;

import java.util.Map;
import java.util.UUID;

/**
 * 工具调用 - LLM 请求调用的工具
 */
public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments
) {
    
    /**
     * 规范化构造函数
     */
    public ToolCall {
        if (id == null || id.isEmpty()) {
            id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }
        if (arguments != null) {
            arguments = Map.copyOf(arguments);
        }
    }
    
    /**
     * 创建工具调用
     */
    public static ToolCall of(String name, Map<String, Object> arguments) {
        return new ToolCall(null, name, arguments);
    }
    
    /**
     * 创建带 ID 的工具调用
     */
    public static ToolCall withId(String id, String name, Map<String, Object> arguments) {
        return new ToolCall(id, name, arguments);
    }
    
    /**
     * 获取字符串参数
     */
    public String getString(String key) {
        Object value = arguments != null ? arguments.get(key) : null;
        return value != null ? value.toString() : null;
    }
    
    /**
     * 获取整数参数
     */
    public Integer getInt(String key) {
        Object value = arguments != null ? arguments.get(key) : null;
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * 获取布尔参数
     */
    public Boolean getBoolean(String key) {
        Object value = arguments != null ? arguments.get(key) : null;
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return null;
    }
}
