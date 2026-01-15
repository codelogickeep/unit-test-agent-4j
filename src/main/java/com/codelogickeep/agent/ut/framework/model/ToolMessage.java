package com.codelogickeep.agent.ut.framework.model;

/**
 * 工具消息 - 工具执行结果
 */
public record ToolMessage(
        String toolCallId,
        String name,
        String content
) implements Message {
    
    @Override
    public String role() {
        return "tool";
    }
    
    /**
     * 创建工具执行结果消息
     */
    public static ToolMessage of(String toolCallId, String name, String content) {
        return new ToolMessage(toolCallId, name, content);
    }
    
    /**
     * 创建工具执行成功消息
     */
    public static ToolMessage success(ToolCall call, String result) {
        return new ToolMessage(call.id(), call.name(), result);
    }
    
    /**
     * 创建工具执行错误消息
     */
    public static ToolMessage error(ToolCall call, String errorMessage) {
        return new ToolMessage(call.id(), call.name(), "Error: " + errorMessage);
    }
}
