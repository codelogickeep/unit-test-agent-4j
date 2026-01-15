package com.codelogickeep.agent.ut.framework.context;

import com.codelogickeep.agent.ut.framework.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上下文管理器 - 管理对话历史
 * 
 * 特性：
 * - 支持最大消息数限制（滑动窗口）
 * - System 消息始终保留
 * - 支持清除上下文（保留 System）
 * - 简单的 Token 估算
 */
public class ContextManager {
    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);
    
    private final List<Message> messages = new ArrayList<>();
    private final int maxMessages;
    private int estimatedTokens = 0;
    
    /**
     * 创建上下文管理器
     * 
     * @param maxMessages 最大消息数（包括 system 消息）
     */
    public ContextManager(int maxMessages) {
        this.maxMessages = maxMessages;
    }
    
    /**
     * 创建默认上下文管理器（20条消息）
     */
    public ContextManager() {
        this(20);
    }
    
    /**
     * 设置系统消息（始终在首位）
     */
    public void setSystemMessage(String content) {
        SystemMessage systemMsg = new SystemMessage(content);
        
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage) {
            messages.set(0, systemMsg);
        } else {
            messages.add(0, systemMsg);
        }
        
        recalculateTokens();
    }
    
    /**
     * 获取系统消息
     */
    public String getSystemMessage() {
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage sys) {
            return sys.content();
        }
        return null;
    }
    
    /**
     * 添加用户消息
     */
    public void addUserMessage(String content) {
        addMessage(new UserMessage(content));
    }
    
    /**
     * 添加助手消息
     */
    public void addAssistantMessage(String content) {
        addMessage(AssistantMessage.text(content));
    }
    
    /**
     * 添加助手消息（带工具调用）
     */
    public void addAssistantMessage(String content, List<ToolCall> toolCalls) {
        addMessage(new AssistantMessage(content, toolCalls));
    }
    
    /**
     * 添加工具消息
     */
    public void addToolMessage(String toolCallId, String name, String content) {
        addMessage(new ToolMessage(toolCallId, name, content));
    }
    
    /**
     * 添加消息
     */
    public void addMessage(Message message) {
        messages.add(message);
        estimatedTokens += estimateTokens(message.content());
        
        trimIfNeeded();
    }
    
    /**
     * 清除上下文（保留 System 消息）
     */
    public void clear() {
        Message systemMsg = null;
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage) {
            systemMsg = messages.get(0);
        }
        
        messages.clear();
        estimatedTokens = 0;
        
        if (systemMsg != null) {
            messages.add(systemMsg);
            estimatedTokens = estimateTokens(systemMsg.content());
        }
        
        log.debug("Context cleared, keeping system message");
    }
    
    /**
     * 完全清除上下文（包括 System 消息）
     */
    public void clearAll() {
        messages.clear();
        estimatedTokens = 0;
        log.debug("Context completely cleared");
    }
    
    /**
     * 获取所有消息
     */
    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }
    
    /**
     * 获取消息数量
     */
    public int size() {
        return messages.size();
    }
    
    /**
     * 获取估算的 Token 数
     */
    public int getEstimatedTokens() {
        return estimatedTokens;
    }
    
    /**
     * 检查是否为空
     */
    public boolean isEmpty() {
        return messages.isEmpty();
    }
    
    /**
     * 检查是否只有 System 消息
     */
    public boolean hasOnlySystemMessage() {
        return messages.size() == 1 && messages.get(0) instanceof SystemMessage;
    }
    
    /**
     * 创建当前上下文的快照副本
     */
    public ContextManager snapshot() {
        ContextManager copy = new ContextManager(this.maxMessages);
        copy.messages.addAll(this.messages);
        copy.estimatedTokens = this.estimatedTokens;
        return copy;
    }
    
    /**
     * 按需裁剪消息（保持在限制内）
     * 
     * 规则：
     * 1. System 消息始终保留在首位
     * 2. 对话必须以 user 消息开始（在 system 之后）
     * 3. 删除最旧的非关键消息
     */
    private void trimIfNeeded() {
        while (messages.size() > maxMessages) {
            // 找到可以删除的消息索引
            int indexToRemove = findRemovableMessageIndex();
            
            if (indexToRemove < 0) {
                break;  // 没有可删除的消息
            }
            
            Message removed = messages.remove(indexToRemove);
            estimatedTokens -= estimateTokens(removed.content());
            log.debug("Trimmed message at index {} (role={})", indexToRemove, removed.role());
        }
        
        // 确保对话序列合法：必须以 system 或 user 开始
        ensureValidMessageSequence();
    }
    
    /**
     * 找到可以删除的消息索引
     */
    private int findRemovableMessageIndex() {
        // 跳过 SystemMessage 和第一个 UserMessage
        int firstUserIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                firstUserIndex = i;
                break;
            }
        }
        
        // 从第一个 UserMessage 之后开始找可删除的消息
        int startIndex = (firstUserIndex >= 0) ? firstUserIndex + 1 : 1;
        
        for (int i = startIndex; i < messages.size(); i++) {
            Message msg = messages.get(i);
            // 不删除 SystemMessage
            if (msg instanceof SystemMessage) {
                continue;
            }
            return i;
        }
        
        return -1;  // 没有可删除的消息
    }
    
    /**
     * 确保消息序列合法
     */
    private void ensureValidMessageSequence() {
        if (messages.isEmpty()) {
            return;
        }
        
        // 检查第一条消息（可能是 system）
        int checkIndex = 0;
        if (messages.get(0) instanceof SystemMessage) {
            if (messages.size() == 1) {
                return;  // 只有 system 消息，合法
            }
            checkIndex = 1;
        }
        
        // 第一条非 system 消息必须是 user
        if (checkIndex < messages.size() && !(messages.get(checkIndex) instanceof UserMessage)) {
            // 序列非法，插入一个补救的 user 消息
            log.warn("Invalid message sequence detected: first non-system message is not user. Inserting placeholder.");
            messages.add(checkIndex, new UserMessage("[Context truncated due to length limit. Please continue.]"));
        }
    }
    
    /**
     * 简单的 Token 估算（约 4 字符 = 1 token）
     */
    private int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return content.length() / 4 + 1;
    }
    
    /**
     * 重新计算 Token 数
     */
    private void recalculateTokens() {
        estimatedTokens = 0;
        for (Message msg : messages) {
            estimatedTokens += estimateTokens(msg.content());
        }
    }
    
    @Override
    public String toString() {
        return String.format("ContextManager[messages=%d, tokens≈%d, max=%d]", 
                messages.size(), estimatedTokens, maxMessages);
    }
}
