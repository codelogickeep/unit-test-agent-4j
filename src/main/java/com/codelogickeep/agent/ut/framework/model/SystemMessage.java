package com.codelogickeep.agent.ut.framework.model;

/**
 * 系统消息 - 用于设置 AI 角色和行为
 */
public record SystemMessage(String content) implements Message {
    
    @Override
    public String role() {
        return "system";
    }
    
    public static SystemMessage of(String content) {
        return new SystemMessage(content);
    }
}
