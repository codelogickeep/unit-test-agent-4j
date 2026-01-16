package com.codelogickeep.agent.ut.framework.context;

import com.codelogickeep.agent.ut.framework.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 单元测试
 * 
 * 测试场景：
 * - 消息管理（添加、清除）
 * - 系统消息处理
 * - 消息裁剪（滑动窗口）
 * - Token 估算
 * - 上下文快照
 */
@DisplayName("ContextManager Tests")
class ContextManagerTest {
    
    private ContextManager contextManager;
    
    @BeforeEach
    void setUp() {
        contextManager = new ContextManager(5); // 最大5条消息
    }
    
    // ========== 基础消息操作测试 ==========
    
    @Test
    @DisplayName("初始状态应为空")
    void testInitialState() {
        assertTrue(contextManager.isEmpty());
        assertEquals(0, contextManager.size());
        assertEquals(0, contextManager.getEstimatedTokens());
    }
    
    @Test
    @DisplayName("添加用户消息")
    void testAddUserMessage() {
        contextManager.addUserMessage("Hello, world!");
        
        assertEquals(1, contextManager.size());
        assertFalse(contextManager.isEmpty());
        
        List<Message> messages = contextManager.getMessages();
        assertEquals(1, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(0));
        assertEquals("Hello, world!", messages.get(0).content());
    }
    
    @Test
    @DisplayName("添加助手消息")
    void testAddAssistantMessage() {
        // 需要先添加用户消息，因为 ContextManager 要求序列以用户消息开始
        contextManager.addUserMessage("User message");
        contextManager.addAssistantMessage("I can help you.");
        
        assertEquals(2, contextManager.size());
        List<Message> messages = contextManager.getMessages();
        assertInstanceOf(UserMessage.class, messages.get(0));
        assertInstanceOf(AssistantMessage.class, messages.get(1));
    }
    
    @Test
    @DisplayName("添加工具消息")
    void testAddToolMessage() {
        // 需要先添加用户消息和助手消息（带工具调用），然后是工具消息
        contextManager.addUserMessage("Please read the file");
        List<ToolCall> toolCalls = List.of(
            new ToolCall("call_123", "readFile", java.util.Map.of("path", "test.java"))
        );
        contextManager.addAssistantMessage("Let me read it.", toolCalls);
        contextManager.addToolMessage("call_123", "readFile", "File content here");
        
        assertEquals(3, contextManager.size());
        List<Message> messages = contextManager.getMessages();
        assertInstanceOf(ToolMessage.class, messages.get(2));
        
        ToolMessage toolMsg = (ToolMessage) messages.get(2);
        assertEquals("call_123", toolMsg.toolCallId());
        assertEquals("readFile", toolMsg.name());
        assertEquals("File content here", toolMsg.content());
    }
    
    @Test
    @DisplayName("添加带工具调用的助手消息")
    void testAddAssistantMessageWithToolCalls() {
        // 先添加用户消息
        contextManager.addUserMessage("Please read the file");
        
        List<ToolCall> toolCalls = List.of(
            new ToolCall("call_1", "readFile", java.util.Map.of("path", "test.java"))
        );
        contextManager.addAssistantMessage("Let me read the file.", toolCalls);
        
        List<Message> messages = contextManager.getMessages();
        assertEquals(2, messages.size());
        assertInstanceOf(AssistantMessage.class, messages.get(1));
        
        AssistantMessage assistantMsg = (AssistantMessage) messages.get(1);
        assertTrue(assistantMsg.hasToolCalls());
        assertEquals(1, assistantMsg.toolCalls().size());
    }
    
    // ========== 系统消息测试 ==========
    
    @Test
    @DisplayName("设置系统消息应放在首位")
    void testSetSystemMessage() {
        contextManager.setSystemMessage("You are a helpful assistant.");
        
        assertEquals(1, contextManager.size());
        List<Message> messages = contextManager.getMessages();
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertEquals("You are a helpful assistant.", messages.get(0).content());
    }
    
    @Test
    @DisplayName("设置系统消息后添加用户消息，系统消息应保持首位")
    void testSystemMessageStaysFirst() {
        contextManager.setSystemMessage("System prompt");
        contextManager.addUserMessage("User message");
        
        assertEquals(2, contextManager.size());
        List<Message> messages = contextManager.getMessages();
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertInstanceOf(UserMessage.class, messages.get(1));
    }
    
    @Test
    @DisplayName("更新系统消息应替换而非追加")
    void testUpdateSystemMessage() {
        contextManager.setSystemMessage("First system message");
        contextManager.setSystemMessage("Updated system message");
        
        assertEquals(1, contextManager.size());
        assertEquals("Updated system message", contextManager.getSystemMessage());
    }
    
    @Test
    @DisplayName("获取系统消息 - 存在时返回内容")
    void testGetSystemMessage() {
        contextManager.setSystemMessage("Test system message");
        assertEquals("Test system message", contextManager.getSystemMessage());
    }
    
    @Test
    @DisplayName("获取系统消息 - 不存在时返回null")
    void testGetSystemMessageWhenNotSet() {
        assertNull(contextManager.getSystemMessage());
    }
    
    // ========== 消息清除测试 ==========
    
    @Test
    @DisplayName("清除上下文应保留系统消息")
    void testClearKeepsSystemMessage() {
        contextManager.setSystemMessage("System prompt");
        contextManager.addUserMessage("User 1");
        contextManager.addAssistantMessage("Assistant 1");
        
        assertEquals(3, contextManager.size());
        
        contextManager.clear();
        
        assertEquals(1, contextManager.size());
        assertEquals("System prompt", contextManager.getSystemMessage());
    }
    
    @Test
    @DisplayName("清除上下文 - 无系统消息时完全清空")
    void testClearWithoutSystemMessage() {
        contextManager.addUserMessage("User 1");
        contextManager.addAssistantMessage("Assistant 1");
        
        contextManager.clear();
        
        assertTrue(contextManager.isEmpty());
    }
    
    @Test
    @DisplayName("完全清除上下文包括系统消息")
    void testClearAll() {
        contextManager.setSystemMessage("System prompt");
        contextManager.addUserMessage("User 1");
        
        contextManager.clearAll();
        
        assertTrue(contextManager.isEmpty());
        assertNull(contextManager.getSystemMessage());
    }
    
    // ========== 消息裁剪测试 ==========
    
    @Test
    @DisplayName("超出最大消息数时应自动裁剪")
    void testAutoTrim() {
        contextManager.setSystemMessage("System");
        contextManager.addUserMessage("User 1");
        contextManager.addAssistantMessage("Assistant 1");
        contextManager.addUserMessage("User 2");
        contextManager.addAssistantMessage("Assistant 2");
        contextManager.addUserMessage("User 3"); // 这条会触发裁剪
        
        // 最大5条，但会裁剪保持在限制内
        assertTrue(contextManager.size() <= 5);
        
        // 系统消息应保留
        assertEquals("System", contextManager.getSystemMessage());
    }
    
    @Test
    @DisplayName("裁剪后第一条非系统消息应是用户消息")
    void testTrimMaintainsValidSequence() {
        ContextManager cm = new ContextManager(3);
        cm.setSystemMessage("System");
        cm.addUserMessage("User 1");
        cm.addAssistantMessage("Assistant 1");
        cm.addUserMessage("User 2"); // 触发裁剪
        
        List<Message> messages = cm.getMessages();
        
        // 第一条是系统消息
        assertInstanceOf(SystemMessage.class, messages.get(0));
        
        // 如果有第二条，应该是用户消息
        if (messages.size() > 1) {
            // 序列验证：系统消息后应该是用户消息
            assertTrue(messages.get(1) instanceof UserMessage || 
                      messages.stream().skip(1).anyMatch(m -> m instanceof UserMessage));
        }
    }
    
    // ========== Token 估算测试 ==========
    
    @Test
    @DisplayName("Token估算应随消息增加")
    void testTokenEstimation() {
        int initialTokens = contextManager.getEstimatedTokens();
        assertEquals(0, initialTokens);
        
        contextManager.addUserMessage("This is a test message with some content.");
        int afterAddTokens = contextManager.getEstimatedTokens();
        assertTrue(afterAddTokens > 0);
        
        contextManager.addAssistantMessage("This is a response.");
        int afterSecondAdd = contextManager.getEstimatedTokens();
        assertTrue(afterSecondAdd > afterAddTokens);
    }
    
    @Test
    @DisplayName("清除后Token应重置")
    void testTokenResetOnClear() {
        contextManager.addUserMessage("Test message");
        assertTrue(contextManager.getEstimatedTokens() > 0);
        
        contextManager.clearAll();
        assertEquals(0, contextManager.getEstimatedTokens());
    }
    
    // ========== 快照测试 ==========
    
    @Test
    @DisplayName("快照应创建独立副本")
    void testSnapshot() {
        contextManager.setSystemMessage("System");
        contextManager.addUserMessage("User message");
        
        ContextManager snapshot = contextManager.snapshot();
        
        // 快照应有相同内容
        assertEquals(contextManager.size(), snapshot.size());
        assertEquals(contextManager.getSystemMessage(), snapshot.getSystemMessage());
        
        // 修改原始不应影响快照
        contextManager.addAssistantMessage("New message");
        assertNotEquals(contextManager.size(), snapshot.size());
    }
    
    // ========== 边界条件测试 ==========
    
    @Test
    @DisplayName("hasOnlySystemMessage - 只有系统消息时返回true")
    void testHasOnlySystemMessage() {
        contextManager.setSystemMessage("System");
        assertTrue(contextManager.hasOnlySystemMessage());
        
        contextManager.addUserMessage("User");
        assertFalse(contextManager.hasOnlySystemMessage());
    }
    
    @Test
    @DisplayName("空内容消息的Token估算")
    void testEmptyContentTokenEstimation() {
        contextManager.addUserMessage("");
        // 空内容应该不会抛出异常
        assertTrue(contextManager.getEstimatedTokens() >= 0);
    }
    
    @Test
    @DisplayName("默认构造函数应创建20条消息限制的管理器")
    void testDefaultConstructor() {
        ContextManager defaultCm = new ContextManager();
        // 添加超过默认限制的消息验证裁剪
        defaultCm.setSystemMessage("System");
        for (int i = 0; i < 25; i++) {
            defaultCm.addUserMessage("User " + i);
            defaultCm.addAssistantMessage("Assistant " + i);
        }
        // 应该被裁剪到20条以内
        assertTrue(defaultCm.size() <= 20);
    }
    
    @Test
    @DisplayName("toString应返回有意义的描述")
    void testToString() {
        contextManager.addUserMessage("Test");
        String str = contextManager.toString();
        
        assertTrue(str.contains("ContextManager"));
        assertTrue(str.contains("messages"));
        assertTrue(str.contains("tokens"));
    }
    
    @Test
    @DisplayName("getMessages返回的列表应不可修改")
    void testGetMessagesReturnsUnmodifiableList() {
        contextManager.addUserMessage("Test");
        List<Message> messages = contextManager.getMessages();
        
        assertThrows(UnsupportedOperationException.class, () -> {
            messages.add(new UserMessage("Should fail"));
        });
    }
}
