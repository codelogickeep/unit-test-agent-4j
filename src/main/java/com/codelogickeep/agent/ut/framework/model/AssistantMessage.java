package com.codelogickeep.agent.ut.framework.model;

import java.util.List;

/**
 * AI 助手消息 - LLM 响应
 * 
 * 注意：content 始终存在（可以为空字符串），这解决了智谱 AI 1214 错误
 */
public record AssistantMessage(
        String content,
        List<ToolCall> toolCalls
) implements Message {
    
    /**
     * 规范化构造函数，确保 content 不为 null
     */
    public AssistantMessage {
        // 确保 content 始终存在，解决智谱 AI 兼容性问题
        if (content == null) {
            content = "";
        }
        // 确保 toolCalls 是不可变的
        if (toolCalls != null) {
            toolCalls = List.copyOf(toolCalls);
        }
    }
    
    @Override
    public String role() {
        return "assistant";
    }
    
    /**
     * 检查是否有工具调用
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
    
    /**
     * 创建纯文本响应
     */
    public static AssistantMessage text(String content) {
        return new AssistantMessage(content, null);
    }
    
    /**
     * 创建带工具调用的响应
     */
    public static AssistantMessage withToolCalls(String content, List<ToolCall> toolCalls) {
        return new AssistantMessage(content, toolCalls);
    }
    
    /**
     * 创建仅工具调用的响应（content 为空）
     */
    public static AssistantMessage toolCallsOnly(List<ToolCall> toolCalls) {
        return new AssistantMessage("", toolCalls);
    }
}
