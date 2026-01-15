package com.codelogickeep.agent.ut.framework.adapter;

import com.codelogickeep.agent.ut.framework.executor.StreamingHandler;
import com.codelogickeep.agent.ut.framework.model.AssistantMessage;
import com.codelogickeep.agent.ut.framework.model.Message;
import com.codelogickeep.agent.ut.framework.model.ToolDefinition;

import java.util.List;

/**
 * LLM 适配器接口 - 抽象不同 LLM 提供商的调用
 */
public interface LlmAdapter {
    
    /**
     * 发送聊天请求（同步）
     * 
     * @param messages 消息历史
     * @param tools 可用工具定义
     * @return 助手响应消息
     */
    AssistantMessage chat(List<Message> messages, List<ToolDefinition> tools);
    
    /**
     * 发送聊天请求（流式）
     * 
     * @param messages 消息历史
     * @param tools 可用工具定义
     * @param handler 流式响应处理器
     */
    void chatStream(List<Message> messages, List<ToolDefinition> tools, StreamingHandler handler);
    
    /**
     * 获取适配器名称
     */
    String getName();
    
    /**
     * 测试连接是否正常
     * 
     * @return true 如果连接正常
     */
    default boolean testConnection() {
        try {
            List<Message> testMessages = List.of(
                    new com.codelogickeep.agent.ut.framework.model.UserMessage("Hi")
            );
            AssistantMessage response = chat(testMessages, List.of());
            return response != null && response.content() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
