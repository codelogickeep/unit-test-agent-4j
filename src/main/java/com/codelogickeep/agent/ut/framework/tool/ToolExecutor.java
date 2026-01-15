package com.codelogickeep.agent.ut.framework.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * 工具执行器 - 负责执行单个工具方法
 */
public class ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    
    private final Object instance;
    private final Method method;
    private final ToolParameter[] parameters;
    
    public ToolExecutor(Object instance, Method method, ToolParameter[] parameters) {
        this.instance = instance;
        this.method = method;
        this.parameters = parameters;
        this.method.setAccessible(true);
    }
    
    /**
     * 执行工具方法
     * 
     * @param arguments 参数 Map
     * @return 执行结果字符串
     */
    public String execute(Map<String, Object> arguments) {
        try {
            Object[] args = resolveArguments(arguments);
            Object result = method.invoke(instance, args);
            return resultToString(result);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            log.error("Tool execution failed: {}", cause.getMessage());
            return "Error: " + cause.getMessage();
        } catch (Exception e) {
            log.error("Tool invocation failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
    
    /**
     * 解析参数
     */
    private Object[] resolveArguments(Map<String, Object> arguments) {
        Object[] args = new Object[parameters.length];
        
        for (int i = 0; i < parameters.length; i++) {
            ToolParameter param = parameters[i];
            Object value = arguments != null ? arguments.get(param.name()) : null;
            
            if (value == null && param.required()) {
                throw new IllegalArgumentException("Missing required parameter: " + param.name());
            }
            
            args[i] = convertValue(value, param.type());
        }
        
        return args;
    }
    
    /**
     * 转换参数值到目标类型
     */
    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return getDefaultValue(targetType);
        }
        
        // 如果类型已匹配
        if (targetType.isInstance(value)) {
            return value;
        }
        
        String strValue = value.toString();
        
        // 基本类型转换
        if (targetType == String.class) {
            return strValue;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(strValue);
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(strValue);
        }
        if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(strValue);
        }
        if (targetType == float.class || targetType == Float.class) {
            return Float.parseFloat(strValue);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(strValue);
        }
        
        // 无法转换，返回原始值
        return value;
    }
    
    /**
     * 获取类型默认值
     */
    private Object getDefaultValue(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0.0;
            if (type == float.class) return 0.0f;
            if (type == boolean.class) return false;
            if (type == char.class) return '\0';
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
        }
        return null;
    }
    
    /**
     * 将结果转换为字符串
     */
    private String resultToString(Object result) {
        if (result == null) {
            return "Success (no return value)";
        }
        return result.toString();
    }
    
    public Method getMethod() {
        return method;
    }
    
    public ToolParameter[] getParameters() {
        return parameters;
    }
    
    /**
     * 工具参数定义
     */
    public record ToolParameter(
            String name,
            String description,
            Class<?> type,
            boolean required
    ) {}
}
