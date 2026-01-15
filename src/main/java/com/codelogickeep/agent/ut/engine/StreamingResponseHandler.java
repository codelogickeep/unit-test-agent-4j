package com.codelogickeep.agent.ut.engine;

import dev.langchain4j.service.TokenStream;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.PrintStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 流式响应处理器 - 统一处理 LLM 流式输出
 * 
 * 特性：
 * - 实时打印响应内容
 * - 收集完整响应
 * - 统计 token 数量
 * - 错误处理
 */
@Slf4j
public class StreamingResponseHandler {

    private final PrintStream outputStream;
    private final boolean showProgress;

    public StreamingResponseHandler() {
        this(System.out, true);
    }

    public StreamingResponseHandler(PrintStream outputStream, boolean showProgress) {
        this.outputStream = outputStream;
        this.showProgress = showProgress;
    }

    /**
     * 处理 TokenStream 并返回完整响应
     * 
     * @param tokenStream LLM 的流式响应
     * @return 处理结果
     */
    public StreamingResult handle(TokenStream tokenStream) {
        return handle(tokenStream, null);
    }

    /**
     * 处理 TokenStream 并返回完整响应
     * 
     * @param tokenStream LLM 的流式响应
     * @param onToken     每个 token 的回调（可选）
     * @return 处理结果
     */
    public StreamingResult handle(TokenStream tokenStream, Consumer<String> onToken) {
        StringBuilder contentBuilder = new StringBuilder();
        CompletableFuture<StreamingResult> future = new CompletableFuture<>();
        long startTime = System.currentTimeMillis();
        int[] tokenCount = { 0 };

        tokenStream
                .onPartialResponse(token -> {
                    tokenCount[0]++;

                    // 打印到输出流
                    if (showProgress) {
                        outputStream.print(token);
                        outputStream.flush();
                    }

                    // 收集内容
                    contentBuilder.append(token);

                    // 调用回调
                    if (onToken != null) {
                        onToken.accept(token);
                    }
                })
                .onCompleteResponse(response -> {
                    if (showProgress) {
                        outputStream.println();
                    }

                    long duration = System.currentTimeMillis() - startTime;

                    StreamingResult result = StreamingResult.builder()
                            .success(true)
                            .content(contentBuilder.toString())
                            .tokenCount(tokenCount[0])
                            .durationMs(duration)
                            .build();

                    log.debug("Streaming completed: {} tokens in {}ms", tokenCount[0], duration);
                    future.complete(result);
                })
                .onError(error -> {
                    log.error("Streaming error: {} - {}", error.getClass().getName(), error.getMessage());
                    if (error.getMessage() == null || error.getMessage().isEmpty()) {
                        log.error("Full error stack trace:", error);
                    }

                    long duration = System.currentTimeMillis() - startTime;

                    StreamingResult result = StreamingResult.builder()
                            .success(false)
                            .content(contentBuilder.toString())
                            .tokenCount(tokenCount[0])
                            .durationMs(duration)
                            .error(error)
                            .build();

                    future.complete(result);
                })
                .start();

        try {
            return future.join();
        } catch (Exception e) {
            log.error("Failed to process streaming response", e);
            return StreamingResult.builder()
                    .success(false)
                    .content(contentBuilder.toString())
                    .tokenCount(tokenCount[0])
                    .durationMs(System.currentTimeMillis() - startTime)
                    .error(e)
                    .build();
        }
    }

    /**
     * 静默处理（不打印输出）
     */
    public StreamingResult handleSilently(TokenStream tokenStream) {
        StreamingResponseHandler silentHandler = new StreamingResponseHandler(outputStream, false);
        return silentHandler.handle(tokenStream);
    }

    // ==================== 结果类 ====================

    @Data
    @Builder
    public static class StreamingResult {
        private boolean success;
        private String content;
        private int tokenCount;
        private long durationMs;
        private Throwable error;

        public boolean hasContent() {
            return content != null && !content.isEmpty();
        }

        public String getSummary() {
            if (success) {
                return String.format("Completed: %d tokens, %d chars, %dms",
                        tokenCount, content != null ? content.length() : 0, durationMs);
            } else {
                return String.format("Failed after %d tokens in %dms: %s",
                        tokenCount, durationMs, error != null ? error.getMessage() : "Unknown error");
            }
        }
    }
}
