package com.codelogickeep.agent.ut.framework.executor;

import com.codelogickeep.agent.ut.framework.model.ToolCall;

import java.util.List;

/**
 * 流式响应处理器 - 处理 LLM 的流式输出
 */
public interface StreamingHandler {
    
    /**
     * 收到文本 Token
     */
    void onToken(String token);
    
    /**
     * 收到工具调用
     */
    void onToolCall(ToolCall toolCall);
    
    /**
     * 响应完成
     * 
     * @param fullContent 完整的响应内容
     * @param toolCalls 所有工具调用（如果有）
     */
    void onComplete(String fullContent, List<ToolCall> toolCalls);
    
    /**
     * 发生错误
     */
    void onError(Throwable error);
    
    /**
     * 默认实现 - 打印到控制台
     */
    static StreamingHandler console() {
        return new StreamingHandler() {
            private final StringBuilder content = new StringBuilder();
            
            @Override
            public void onToken(String token) {
                System.out.print(token);
                content.append(token);
            }
            
            @Override
            public void onToolCall(ToolCall toolCall) {
                System.out.printf("\n[Tool Call: %s(%s)]%n", toolCall.name(), toolCall.arguments());
            }
            
            @Override
            public void onComplete(String fullContent, List<ToolCall> toolCalls) {
                System.out.println("\n--- Response Complete ---");
            }
            
            @Override
            public void onError(Throwable error) {
                System.err.println("\n[Error] " + error.getMessage());
            }
        };
    }
    
    /**
     * 静默实现 - 不输出任何内容
     */
    static StreamingHandler silent() {
        return new StreamingHandler() {
            @Override
            public void onToken(String token) {}
            
            @Override
            public void onToolCall(ToolCall toolCall) {}
            
            @Override
            public void onComplete(String fullContent, List<ToolCall> toolCalls) {}
            
            @Override
            public void onError(Throwable error) {}
        };
    }
}
