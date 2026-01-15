package com.codelogickeep.agent.ut.framework.executor;

import com.codelogickeep.agent.ut.framework.adapter.LlmAdapter;
import com.codelogickeep.agent.ut.framework.context.ContextManager;
import com.codelogickeep.agent.ut.framework.model.*;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 执行器 - 核心 ReAct 循环
 * 
 * 流程：
 * 1. 用户输入 → LLM
 * 2. LLM 响应（可能包含工具调用）
 * 3. 执行工具 → 结果返回 LLM
 * 4. 重复直到 LLM 不再调用工具
 */
public class AgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);
    
    private final LlmAdapter llmAdapter;
    private final ToolRegistry toolRegistry;
    private final ContextManager contextManager;
    private final int maxIterations;
    private final long timeoutMs;
    
    private AgentExecutor(Builder builder) {
        this.llmAdapter = builder.llmAdapter;
        this.toolRegistry = builder.toolRegistry;
        this.contextManager = builder.contextManager;
        this.maxIterations = builder.maxIterations;
        this.timeoutMs = builder.timeoutMs;
    }
    
    /**
     * 执行 Agent 任务（同步）
     */
    public AgentResult run(String userMessage) {
        log.info("Starting agent execution: {}", 
                userMessage.substring(0, Math.min(100, userMessage.length())) + "...");
        
        long startTime = System.currentTimeMillis();
        contextManager.addUserMessage(userMessage);
        
        int iteration = 0;
        int totalToolCalls = 0;
        String lastContent = "";
        
        try {
            while (iteration < maxIterations) {
                iteration++;
                
                // 检查超时
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    log.warn("Agent execution timeout after {}ms", timeoutMs);
                    return AgentResult.error("Execution timeout");
                }
                
                log.debug("Iteration #{}", iteration);
                
                // 1. 调用 LLM
                AssistantMessage response = llmAdapter.chat(
                        contextManager.getMessages(),
                        toolRegistry.getDefinitions()
                );
                
                // 2. 记录响应
                contextManager.addMessage(response);
                lastContent = response.content();
                
                // 3. 检查是否需要调用工具
                if (!response.hasToolCalls()) {
                    // 没有工具调用，任务完成
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("Agent completed in {} iterations, {} tool calls, {}ms", 
                            iteration, totalToolCalls, duration);
                    return AgentResult.success(lastContent, iteration, totalToolCalls, duration);
                }
                
                // 4. 执行工具调用
                for (ToolCall toolCall : response.toolCalls()) {
                    totalToolCalls++;
                    log.info("Executing tool: {}", toolCall.name());
                    
                    String result = toolRegistry.invoke(toolCall);
                    contextManager.addToolMessage(toolCall.id(), toolCall.name(), result);
                }
            }
            
            // 达到最大迭代次数
            log.warn("Max iterations ({}) reached", maxIterations);
            return AgentResult.maxIterationsReached(iteration, lastContent);
            
        } catch (Exception e) {
            log.error("Agent execution failed", e);
            return AgentResult.error(e);
        }
    }
    
    /**
     * 执行 Agent 任务（流式）
     */
    public void runStream(String userMessage, StreamingHandler handler) {
        log.info("Starting streaming agent execution");
        
        contextManager.addUserMessage(userMessage);
        
        int iteration = 0;
        
        try {
            while (iteration < maxIterations) {
                iteration++;
                
                log.debug("Streaming iteration #{}", iteration);
                
                // 使用 CountDownLatch 等待流式完成
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<String> fullContent = new AtomicReference<>("");
                AtomicReference<List<ToolCall>> toolCallsRef = new AtomicReference<>(new ArrayList<>());
                AtomicReference<Throwable> errorRef = new AtomicReference<>();
                
                llmAdapter.chatStream(
                        contextManager.getMessages(),
                        toolRegistry.getDefinitions(),
                        new StreamingHandler() {
                            private final StringBuilder content = new StringBuilder();
                            
                            @Override
                            public void onToken(String token) {
                                content.append(token);
                                handler.onToken(token);
                            }
                            
                            @Override
                            public void onToolCall(ToolCall toolCall) {
                                toolCallsRef.get().add(toolCall);
                                handler.onToolCall(toolCall);
                            }
                            
                            @Override
                            public void onComplete(String fc, List<ToolCall> tc) {
                                fullContent.set(content.toString());
                                if (tc != null) {
                                    toolCallsRef.set(tc);
                                }
                                latch.countDown();
                            }
                            
                            @Override
                            public void onError(Throwable error) {
                                errorRef.set(error);
                                handler.onError(error);
                                latch.countDown();
                            }
                        }
                );
                
                // 等待流式完成
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    handler.onError(new RuntimeException("Streaming timeout"));
                    return;
                }
                
                // 检查错误
                if (errorRef.get() != null) {
                    return;
                }
                
                // 记录响应
                List<ToolCall> toolCalls = toolCallsRef.get();
                contextManager.addAssistantMessage(fullContent.get(), toolCalls.isEmpty() ? null : toolCalls);
                
                // 检查是否需要工具调用
                if (toolCalls.isEmpty()) {
                    handler.onComplete(fullContent.get(), null);
                    return;
                }
                
                // 执行工具调用
                for (ToolCall toolCall : toolCalls) {
                    String result = toolRegistry.invoke(toolCall);
                    contextManager.addToolMessage(toolCall.id(), toolCall.name(), result);
                }
            }
            
            handler.onError(new RuntimeException("Max iterations reached: " + maxIterations));
            
        } catch (Exception e) {
            log.error("Streaming agent execution failed", e);
            handler.onError(e);
        }
    }
    
    /**
     * 清除上下文（保留 System 消息）
     */
    public void clearContext() {
        contextManager.clear();
    }
    
    /**
     * 获取上下文管理器
     */
    public ContextManager getContextManager() {
        return contextManager;
    }
    
    /**
     * 获取工具注册表
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }
    
    // ==================== Builder ====================
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private LlmAdapter llmAdapter;
        private ToolRegistry toolRegistry;
        private ContextManager contextManager;
        private int maxIterations = 50;
        private long timeoutMs = 300_000;  // 5 分钟
        
        public Builder llmAdapter(LlmAdapter adapter) {
            this.llmAdapter = adapter;
            return this;
        }
        
        public Builder toolRegistry(ToolRegistry registry) {
            this.toolRegistry = registry;
            return this;
        }
        
        public Builder contextManager(ContextManager manager) {
            this.contextManager = manager;
            return this;
        }
        
        public Builder tools(Object... toolInstances) {
            if (this.toolRegistry == null) {
                this.toolRegistry = new ToolRegistry();
            }
            this.toolRegistry.registerAll(toolInstances);
            return this;
        }
        
        public Builder tools(List<Object> toolInstances) {
            if (this.toolRegistry == null) {
                this.toolRegistry = new ToolRegistry();
            }
            this.toolRegistry.registerAll(toolInstances);
            return this;
        }
        
        public Builder systemMessage(String systemMessage) {
            if (this.contextManager == null) {
                this.contextManager = new ContextManager();
            }
            this.contextManager.setSystemMessage(systemMessage);
            return this;
        }
        
        public Builder maxIterations(int max) {
            this.maxIterations = max;
            return this;
        }
        
        public Builder maxMessages(int max) {
            this.contextManager = new ContextManager(max);
            return this;
        }
        
        public Builder timeoutMs(long timeout) {
            this.timeoutMs = timeout;
            return this;
        }
        
        public AgentExecutor build() {
            if (llmAdapter == null) {
                throw new IllegalStateException("LlmAdapter is required");
            }
            if (toolRegistry == null) {
                toolRegistry = new ToolRegistry();
            }
            if (contextManager == null) {
                contextManager = new ContextManager();
            }
            return new AgentExecutor(this);
        }
    }
}
