package com.codelogickeep.agent.ut.framework;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.adapter.LlmAdapter;
import com.codelogickeep.agent.ut.framework.adapter.LlmAdapterFactory;
import com.codelogickeep.agent.ut.framework.context.ContextManager;
import com.codelogickeep.agent.ut.framework.executor.AgentExecutor;
import com.codelogickeep.agent.ut.framework.executor.AgentResult;
import com.codelogickeep.agent.ut.framework.executor.ConsoleStreamingHandler;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 简化版 Agent 编排器 - 使用自研框架
 * 
 * 特性：
 * - 完全脱离 LangChain4j
 * - 精确的上下文管理
 * - 支持迭代模式
 * - 流式输出到控制台
 */
public class SimpleAgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(SimpleAgentOrchestrator.class);

    private final AppConfig config;
    private final LlmAdapter llmAdapter;
    private final ToolRegistry toolRegistry;
    private final int maxIterations;

    public SimpleAgentOrchestrator(AppConfig config, List<Object> tools) {
        this.config = config;
        this.llmAdapter = LlmAdapterFactory.create(config.getLlm());
        this.toolRegistry = new ToolRegistry();
        this.toolRegistry.registerAll(tools);
        this.maxIterations = config.getWorkflow() != null ? config.getWorkflow().getMaxRetries() * 10 : 50;

        log.info("SimpleAgentOrchestrator initialized with {} tools", toolRegistry.size());
    }

    /**
     * 运行 Agent
     */
    public void run(String targetFile) {
        run(targetFile, null);
    }

    /**
     * 运行 Agent
     */
    public void run(String targetFile, String taskContext) {
        boolean iterativeMode = config.getWorkflow() != null && config.getWorkflow().isIterativeMode();

        if (iterativeMode) {
            runIterative(targetFile, taskContext);
        } else {
            runTraditional(targetFile, taskContext);
        }
    }

    /**
     * 传统模式
     */
    private void runTraditional(String targetFile, String taskContext) {
        log.info("Starting Agent (traditional mode) for: {}", targetFile);

        String projectRoot = extractProjectRoot(targetFile);
        String systemPrompt = loadSystemPrompt(projectRoot);

        // 创建执行器
        AgentExecutor executor = AgentExecutor.builder()
                .llmAdapter(llmAdapter)
                .toolRegistry(toolRegistry)
                .systemMessage(systemPrompt)
                .maxMessages(20)
                .maxIterations(maxIterations)
                .timeoutMs(600_000) // 10 分钟
                .build();

        // 构建用户消息
        String userMessage = buildUserMessage(targetFile, taskContext);

        // 流式执行
        ConsoleStreamingHandler handler = new ConsoleStreamingHandler();
        executor.runStream(userMessage, handler);

        try {
            handler.await(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for completion");
        }

        if (handler.isSuccess()) {
            log.info("Agent completed successfully");
        } else {
            log.error("Agent failed: {}", handler.getError() != null ? handler.getError().getMessage() : "Unknown");
        }
    }

    /**
     * 迭代模式 - 每个方法独立上下文
     */
    private void runIterative(String targetFile, String taskContext) {
        log.info("Starting Agent (ITERATIVE mode) for: {}", targetFile);

        String projectRoot = extractProjectRoot(targetFile);
        String systemPrompt = loadSystemPrompt(projectRoot);

        // ===== Phase 1: 初始化 =====
        log.info(">>> Phase 1: Initialization");

        // 智谱 AI 对消息窗口大小敏感，使用较小的窗口（8 条消息）
        AgentExecutor initExecutor = createExecutor(systemPrompt, 8);
        String initPrompt = buildIterativeInitPrompt(targetFile);

        AgentResult initResult = initExecutor.run(initPrompt);
        if (!initResult.success()) {
            log.error("Initialization failed: {}", initResult.errorMessage());
            return;
        }

        // ===== Phase 2: 逐方法迭代 =====
        int maxMethodIterations = 20;

        for (int i = 1; i <= maxMethodIterations; i++) {
            log.info(">>> Phase 2: Method Iteration #{}", i);

            // 每个方法创建新的执行器（清空上下文！）
            AgentExecutor methodExecutor = createExecutor(systemPrompt, 10);
            String methodPrompt = buildIterativeMethodPrompt(targetFile, i);

            // 流式执行
            ConsoleStreamingHandler handler = new ConsoleStreamingHandler();
            methodExecutor.runStream(methodPrompt, handler);

            try {
                handler.await(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            String content = handler.getContent();
            if (content.contains("ITERATION_COMPLETE") || content.contains("All methods completed")) {
                log.info(">>> Iteration completed after {} methods", i);
                break;
            }
        }

        // ===== Phase 3: 汇总 =====
        log.info(">>> Phase 3: Summary");

        AgentExecutor summaryExecutor = createExecutor(systemPrompt, 5);
        AgentResult summaryResult = summaryExecutor.run(
                "Call getIterationProgress() to show the final summary of all tested methods.");

        if (summaryResult.success()) {
            log.info("Iterative test generation completed");
        }
    }

    /**
     * 创建执行器
     */
    private AgentExecutor createExecutor(String systemPrompt, int maxMessages) {
        return AgentExecutor.builder()
                .llmAdapter(llmAdapter)
                .toolRegistry(toolRegistry)
                .systemMessage(systemPrompt)
                .maxMessages(maxMessages)
                .maxIterations(maxIterations)
                .timeoutMs(300_000)
                .build();
    }

    /**
     * 构建用户消息
     */
    private String buildUserMessage(String targetFile, String taskContext) {
        StringBuilder message = new StringBuilder();

        if (taskContext != null && !taskContext.isEmpty()) {
            message.append(taskContext).append("\n\n");
        }

        message.append("Target file: ").append(targetFile);
        return message.toString();
    }

    /**
     * 构建迭代初始化提示
     */
    private String buildIterativeInitPrompt(String targetFile) {
        return String.format("""
                ## ITERATIVE MODE - PHASE 1: INITIALIZATION

                Target file: %s

                Please complete these steps:
                1. Check if test directory exists (directoryExists)
                2. Check if test file exists (fileExists)
                3. Read the source file (readFile)
                4. Analyze method priorities (getPriorityMethods)
                5. Initialize iteration (initMethodIteration)
                6. Create the test file skeleton if it doesn't exist (writeFile)

                After initialization, call getNextMethod() to get the first method.
                Then STOP and wait for next instruction.
                """, targetFile);
    }

    /**
     * 构建迭代方法提示
     */
    private String buildIterativeMethodPrompt(String targetFile, int iteration) {
        return String.format("""
                ## ITERATIVE MODE - PHASE 2: METHOD #%d

                Target file: %s

                ⚠️ THIS IS A FRESH CONTEXT - Previous conversation is cleared.

                Steps:
                1. Call getNextMethod() to get the current method
                2. If "ITERATION_COMPLETE", call getIterationProgress() and STOP
                3. Otherwise:
                   a. Read current test file (readFile)
                   b. Generate tests for this method only
                   c. Append using writeFileFromLine
                   d. checkSyntax → compileProject → executeTest
                   e. getSingleMethodCoverage
                   f. completeCurrentMethod with status

                After completing, STOP.
                """, iteration, targetFile);
    }

    /**
     * 加载系统提示词
     */
    private String loadSystemPrompt(String projectRoot) {
        String defaultPrompt = """
                You are an expert Java QA Engineer. Your task is to analyze Java code and
                generate JUnit 5 tests with high coverage.
                Always use the provided tools to read files, write tests, and run them.
                """;

        if (config.getPrompts() != null && config.getPrompts().containsKey("system")) {
            String pathStr = config.getPrompts().get("system");

            try {
                // 1. 尝试文件系统
                Path path = Paths.get(pathStr);
                if (Files.exists(path)) {
                    return Files.readString(path, StandardCharsets.UTF_8);
                }

                // 2. 尝试 classpath
                String resourcePath = pathStr.startsWith("/") ? pathStr.substring(1) : pathStr;
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (in != null) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to load prompt from {}", pathStr);
            }
        }

        return defaultPrompt;
    }

    /**
     * 提取项目根目录
     */
    private String extractProjectRoot(String targetFile) {
        if (targetFile == null)
            return null;

        String normalized = targetFile.replace("\\", "/");
        int srcMainIndex = normalized.indexOf("/src/main/java/");
        if (srcMainIndex > 0) {
            return normalized.substring(0, srcMainIndex);
        }

        int srcIndex = normalized.indexOf("/src/");
        if (srcIndex > 0) {
            return normalized.substring(0, srcIndex);
        }

        return null;
    }

    /**
     * 测试 LLM 连接
     */
    public boolean testLlmConnection() {
        return LlmAdapterFactory.testConnection(llmAdapter);
    }
}
