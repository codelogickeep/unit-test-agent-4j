package com.codelogickeep.agent.ut.framework.model;

/**
 * 统一消息模型 - 用于 LLM 对话
 * 
 * 使用 sealed interface 确保类型安全
 */
public sealed interface Message permits SystemMessage, UserMessage, AssistantMessage, ToolMessage {
    
    /**
     * 获取消息角色
     */
    String role();
    
    /**
     * 获取消息内容
     */
    String content();
}
