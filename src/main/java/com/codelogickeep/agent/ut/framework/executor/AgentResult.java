package com.codelogickeep.agent.ut.framework.executor;

/**
 * Agent 执行结果
 */
public record AgentResult(
        boolean success,
        String content,
        int iterations,
        int toolCalls,
        long durationMs,
        String errorMessage
) {
    
    /**
     * 创建成功结果
     */
    public static AgentResult success(String content, int iterations, int toolCalls, long durationMs) {
        return new AgentResult(true, content, iterations, toolCalls, durationMs, null);
    }
    
    /**
     * 创建简单成功结果
     */
    public static AgentResult success(String content) {
        return new AgentResult(true, content, 1, 0, 0, null);
    }
    
    /**
     * 创建达到最大迭代次数的结果
     */
    public static AgentResult maxIterationsReached(int iterations, String lastContent) {
        return new AgentResult(false, lastContent, iterations, 0, 0, 
                "Max iterations reached: " + iterations);
    }
    
    /**
     * 创建错误结果
     */
    public static AgentResult error(String message) {
        return new AgentResult(false, null, 0, 0, 0, message);
    }
    
    /**
     * 创建错误结果（带异常）
     */
    public static AgentResult error(Throwable e) {
        return new AgentResult(false, null, 0, 0, 0, e.getMessage());
    }
    
    /**
     * 获取摘要信息
     */
    public String getSummary() {
        if (success) {
            return String.format("Success: %d iterations, %d tool calls, %dms", 
                    iterations, toolCalls, durationMs);
        } else {
            return "Failed: " + (errorMessage != null ? errorMessage : "Unknown error");
        }
    }
}
