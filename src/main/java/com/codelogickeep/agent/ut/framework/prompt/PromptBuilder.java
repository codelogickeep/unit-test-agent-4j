package com.codelogickeep.agent.ut.framework.prompt;

/**
 * Prompt 构建器接口
 */
public interface PromptBuilder {
    /**
     * 构建系统提示词
     */
    String buildSystemPrompt();

    /**
     * 构建用户消息
     */
    String buildUserMessage();
}
