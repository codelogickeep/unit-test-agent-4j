package com.codelogickeep.agent.ut.framework.executor;

import com.codelogickeep.agent.ut.framework.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 控制台流式处理器 - 将 LLM 响应实时输出到控制台
 */
public class ConsoleStreamingHandler implements StreamingHandler {
    private static final Logger log = LoggerFactory.getLogger(ConsoleStreamingHandler.class);
    
    private final PrintStream out;
    private final StringBuilder contentBuilder = new StringBuilder();
    private final AtomicReference<List<ToolCall>> toolCallsRef = new AtomicReference<>();
    private final AtomicReference<Throwable> errorRef = new AtomicReference<>();
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    
    private boolean showToolCalls = true;
    private String prefix = "";
    
    public ConsoleStreamingHandler() {
        this(System.out);
    }
    
    public ConsoleStreamingHandler(PrintStream out) {
        this.out = out;
    }
    
    /**
     * 设置是否显示工具调用
     */
    public ConsoleStreamingHandler showToolCalls(boolean show) {
        this.showToolCalls = show;
        return this;
    }
    
    /**
     * 设置输出前缀
     */
    public ConsoleStreamingHandler prefix(String prefix) {
        this.prefix = prefix != null ? prefix : "";
        return this;
    }
    
    @Override
    public void onToken(String token) {
        out.print(token);
        out.flush();
        contentBuilder.append(token);
    }
    
    @Override
    public void onToolCall(ToolCall toolCall) {
        if (showToolCalls) {
            out.printf("%n%s[Tool: %s]%n", prefix, toolCall.name());
            out.flush();
        }
        log.debug("Tool call received: {}", toolCall.name());
    }
    
    @Override
    public void onComplete(String fullContent, List<ToolCall> toolCalls) {
        this.toolCallsRef.set(toolCalls);
        out.println();
        out.flush();
        completionLatch.countDown();
    }
    
    @Override
    public void onError(Throwable error) {
        this.errorRef.set(error);
        out.printf("%n%s[Error: %s]%n", prefix, error.getMessage());
        out.flush();
        log.error("Streaming error", error);
        completionLatch.countDown();
    }
    
    /**
     * 获取完整响应内容
     */
    public String getContent() {
        return contentBuilder.toString();
    }
    
    /**
     * 获取工具调用列表
     */
    public List<ToolCall> getToolCalls() {
        return toolCallsRef.get();
    }
    
    /**
     * 获取错误（如果有）
     */
    public Throwable getError() {
        return errorRef.get();
    }
    
    /**
     * 检查是否成功
     */
    public boolean isSuccess() {
        return errorRef.get() == null;
    }
    
    /**
     * 等待完成
     */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return completionLatch.await(timeout, unit);
    }
    
    /**
     * 等待完成（默认 5 分钟）
     */
    public boolean await() throws InterruptedException {
        return await(5, TimeUnit.MINUTES);
    }
    
    /**
     * 创建并返回结果
     */
    public StreamingResult toResult() {
        return new StreamingResult(
                isSuccess(),
                getContent(),
                getToolCalls(),
                getError()
        );
    }
    
    /**
     * 流式结果
     */
    public record StreamingResult(
            boolean success,
            String content,
            List<ToolCall> toolCalls,
            Throwable error
    ) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
