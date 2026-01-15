package com.codelogickeep.agent.ut.framework.model;

/**
 * 用户消息 - 用户输入
 */
public record UserMessage(String content) implements Message {
    
    @Override
    public String role() {
        return "user";
    }
    
    public static UserMessage of(String content) {
        return new UserMessage(content);
    }
}
