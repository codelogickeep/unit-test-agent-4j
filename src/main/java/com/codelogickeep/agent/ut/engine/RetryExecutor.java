package com.codelogickeep.agent.ut.engine;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 通用重试执行器 - 提供可配置的重试逻辑
 * 
 * 特性：
 * - 指数退避策略
 * - 可配置的最大重试次数
 * - 详细的执行统计
 * - 异常分类处理
 */
@Slf4j
public class RetryExecutor {

    private final RetryConfig config;

    public RetryExecutor(RetryConfig config) {
        this.config = config;
    }

    public RetryExecutor(int maxRetries) {
        this.config = RetryConfig.builder()
                .maxRetries(maxRetries)
                .build();
    }

    /**
     * 执行带重试的操作
     * 
     * @param operation 要执行的操作
     * @param operationName 操作名称（用于日志）
     * @param <T> 返回类型
     * @return 执行结果
     */
    public <T> ExecutionResult<T> execute(Supplier<T> operation, String operationName) {
        int attempt = 0;
        long startTime = System.currentTimeMillis();
        Exception lastException = null;

        while (attempt <= config.getMaxRetries()) {
            try {
                if (attempt > 0) {
                    log.info("Retrying {} (attempt {}/{})", operationName, attempt, config.getMaxRetries());
                }

                T result = operation.get();
                long duration = System.currentTimeMillis() - startTime;

                log.info("{} completed successfully after {} attempt(s) in {}ms", 
                        operationName, attempt + 1, duration);

                return ExecutionResult.<T>builder()
                        .success(true)
                        .result(result)
                        .attempts(attempt + 1)
                        .durationMs(duration)
                        .build();

            } catch (Exception e) {
                lastException = e;
                attempt++;

                if (attempt <= config.getMaxRetries()) {
                    long waitTime = calculateWaitTime(attempt);
                    log.warn("{} failed on attempt {}: {}. Waiting {}ms before retry...",
                            operationName, attempt, e.getMessage(), waitTime);

                    if (config.isLogStackTrace()) {
                        log.debug("Exception details:", e);
                    }

                    sleep(waitTime);
                } else {
                    log.error("{} failed after {} attempts. Giving up.", operationName, attempt);
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        return ExecutionResult.<T>builder()
                .success(false)
                .attempts(attempt)
                .durationMs(duration)
                .lastException(lastException)
                .build();
    }

    /**
     * 执行带重试的 Runnable
     */
    public ExecutionResult<Void> execute(Runnable operation, String operationName) {
        return execute(() -> {
            operation.run();
            return null;
        }, operationName);
    }

    /**
     * 计算等待时间（指数退避）
     */
    private long calculateWaitTime(int attempt) {
        long baseWait = config.getInitialWaitMs();
        double multiplier = config.getBackoffMultiplier();
        long maxWait = config.getMaxWaitMs();

        long waitTime = (long) (baseWait * Math.pow(multiplier, attempt - 1));
        return Math.min(waitTime, maxWait);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry interrupted", e);
        }
    }

    // ==================== 配置和结果类 ====================

    @Data
    @Builder
    public static class RetryConfig {
        @Builder.Default
        private int maxRetries = 3;
        
        @Builder.Default
        private long initialWaitMs = 1000;
        
        @Builder.Default
        private double backoffMultiplier = 2.0;
        
        @Builder.Default
        private long maxWaitMs = 30000;
        
        @Builder.Default
        private boolean logStackTrace = false;
    }

    @Data
    @Builder
    public static class ExecutionResult<T> {
        private boolean success;
        private T result;
        private int attempts;
        private long durationMs;
        private Exception lastException;

        public String getSummary() {
            if (success) {
                return String.format("Success after %d attempt(s) in %dms", attempts, durationMs);
            } else {
                return String.format("Failed after %d attempt(s) in %dms: %s", 
                        attempts, durationMs, 
                        lastException != null ? lastException.getMessage() : "Unknown error");
            }
        }
    }
}
