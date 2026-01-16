package com.codelogickeep.agent.ut.framework;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.engine.CoverageFeedbackEngine;
import com.codelogickeep.agent.ut.framework.adapter.LlmAdapter;
import com.codelogickeep.agent.ut.framework.adapter.LlmAdapterFactory;
import com.codelogickeep.agent.ut.framework.executor.AgentExecutor;
import com.codelogickeep.agent.ut.framework.executor.AgentResult;
import com.codelogickeep.agent.ut.framework.executor.ConsoleStreamingHandler;
import com.codelogickeep.agent.ut.framework.model.IterationStats;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import com.codelogickeep.agent.ut.tools.BoundaryAnalyzerTool;
import com.codelogickeep.agent.ut.tools.CoverageTool;
import com.codelogickeep.agent.ut.tools.MutationTestTool;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简化版 Agent 编排器 - 使用自研框架
 * 
 * 特性：
 * - 完全脱离 LangChain4j
 * - 精确的上下文管理
 * - 支持迭代模式
 * - 流式输出到控制台
 * - 生成统计报告
 */
public class SimpleAgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(SimpleAgentOrchestrator.class);

    private final AppConfig config;
    private final LlmAdapter llmAdapter;
    private final ToolRegistry toolRegistry;
    private final int maxIterations;

    // 迭代统计
    private IterationStats iterationStats;

    // 覆盖率反馈引擎
    private CoverageFeedbackEngine feedbackEngine;

    public SimpleAgentOrchestrator(AppConfig config, List<Object> tools) {
        this.config = config;
        this.llmAdapter = LlmAdapterFactory.create(config.getLlm());
        this.toolRegistry = new ToolRegistry();
        this.toolRegistry.registerAll(tools);
        this.maxIterations = config.getWorkflow() != null ? config.getWorkflow().getMaxRetries() * 10 : 50;

        // 初始化覆盖率反馈引擎
        initFeedbackEngine(tools);

        log.info("SimpleAgentOrchestrator initialized with {} tools", toolRegistry.size());
    }

    /**
     * 初始化覆盖率反馈引擎
     */
    private void initFeedbackEngine(List<Object> tools) {
        CoverageTool coverageTool = null;
        BoundaryAnalyzerTool boundaryTool = null;
        MutationTestTool mutationTool = null;

        for (Object tool : tools) {
            if (tool instanceof CoverageTool) {
                coverageTool = (CoverageTool) tool;
            } else if (tool instanceof BoundaryAnalyzerTool) {
                boundaryTool = (BoundaryAnalyzerTool) tool;
            } else if (tool instanceof MutationTestTool) {
                mutationTool = (MutationTestTool) tool;
            }
        }

        if (coverageTool != null && boundaryTool != null && mutationTool != null) {
            this.feedbackEngine = new CoverageFeedbackEngine(coverageTool, boundaryTool, mutationTool);
            log.info("CoverageFeedbackEngine initialized");
        } else {
            log.warn("CoverageFeedbackEngine not initialized - missing required tools");
        }
    }

    // 预检查结果，供后续步骤使用
    private PreCheckResult currentPreCheck;

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
        String projectRoot = extractProjectRoot(targetFile);

        // ===== 预检查阶段：编译和覆盖率分析（所有模式共用）=====
        currentPreCheck = performPreCheck(projectRoot, targetFile);
        if (!currentPreCheck.success) {
            log.error("Pre-check failed: {}", currentPreCheck.errorMessage);
            System.err.println("\n❌ Pre-check failed: " + currentPreCheck.errorMessage);
            System.err.println("Please fix the issues above before running the agent.");
            return;
        }

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
     * 
     * 基于预检查的覆盖率数据，按覆盖率从低到高处理方法：
     * 1. 覆盖率已达标的方法 -> 执行变异测试 -> 通过则跳过
     * 2. 覆盖率未达标的方法 -> 生成测试代码
     */
    private void runIterative(String targetFile, String taskContext) {
        log.info("Starting Agent (ITERATIVE mode) for: {}", targetFile);

        String projectRoot = extractProjectRoot(targetFile);
        String systemPrompt = loadSystemPrompt(projectRoot);
        int coverageThreshold = config.getWorkflow() != null ? config.getWorkflow().getCoverageThreshold() : 80;

        // 初始化统计
        iterationStats = new IterationStats(targetFile);

        // ===== 获取方法覆盖率列表（按覆盖率排序，低的在前）=====
        List<MethodCoverageInfo> methodsToProcess = currentPreCheck != null
                ? currentPreCheck.getMethodsSortedByCoverage()
                : new ArrayList<>();

        if (methodsToProcess.isEmpty()) {
            log.info("No method coverage info available, falling back to LLM-driven iteration");
            // 如果没有覆盖率数据，使用 LLM 驱动的迭代
            runIterativeFallback(targetFile, taskContext, systemPrompt, projectRoot);
            return;
        }

        log.info("📊 Found {} methods to process (sorted by coverage):", methodsToProcess.size());
        for (MethodCoverageInfo m : methodsToProcess) {
            log.info("   - {} [{}] Line: {}%, Branch: {}%",
                    m.methodName, m.priority,
                    String.format("%.1f", m.lineCoverage),
                    String.format("%.1f", m.branchCoverage));
        }

        // ===== Phase 1: 初始化 =====
        log.info(">>> Phase 1: Initialization");

        AgentExecutor initExecutor = createExecutor(systemPrompt, 8);
        initExecutor.setTokenStatsCallback((prompt, response) -> {
            iterationStats.recordPromptSize(prompt);
            iterationStats.recordResponseSize(response);
        });

        String initPrompt = buildIterativeInitPrompt(targetFile);
        AgentResult initResult = initExecutor.run(initPrompt);
        if (!initResult.success()) {
            log.error("Initialization failed: {}", initResult.errorMessage());
            return;
        }

        // ===== Phase 2: 逐方法迭代（基于覆盖率数据）=====
        int processedCount = 0;
        int skippedCount = 0;
        final int maxMethodRetries = 3;

        for (int i = 0; i < methodsToProcess.size(); i++) {
            MethodCoverageInfo methodInfo = methodsToProcess.get(i);
            log.info(">>> Phase 2: Method #{} - {} [{}]", i + 1, methodInfo.methodName, methodInfo.priority);

            // 创建方法统计，使用实际方法名和初始覆盖率
            IterationStats.MethodStats currentMethodStats = iterationStats.startMethod(
                    methodInfo.methodName,
                    methodInfo.priority,
                    methodInfo.lineCoverage);

            // 检查是否已达到覆盖率要求
            if (methodInfo.lineCoverage >= coverageThreshold) {
                log.info("📊 Method {} already has {}% coverage (threshold: {}%)",
                        methodInfo.methodName, String.format("%.1f", methodInfo.lineCoverage), coverageThreshold);

                // 覆盖率已达标，直接跳过该方法
                log.info("✅ Method {} coverage sufficient - SKIPPING", methodInfo.methodName);
                currentMethodStats.markSkipped("Coverage " + String.format("%.1f", methodInfo.lineCoverage) + "% >= "
                        + coverageThreshold + "%");
                currentMethodStats.complete("SKIPPED", methodInfo.lineCoverage);
                skippedCount++;
                continue;
            }

            // 需要生成测试
            processedCount++;
            int methodRetryCount = 0;
            boolean methodCompleted = false;

            while (!methodCompleted && methodRetryCount < maxMethodRetries) {
                // 每个方法创建新的执行器（清空上下文！）
                AgentExecutor methodExecutor = createExecutor(systemPrompt, 10);

                methodExecutor.setTokenStatsCallback((prompt, response) -> {
                    currentMethodStats.addPromptTokens(prompt);
                    currentMethodStats.addResponseTokens(response);
                    log.info("📊 Method {} - Prompt: {} tokens, Response: {} tokens",
                            methodInfo.methodName, prompt, response);
                });

                // 构建针对特定方法的提示词
                String methodPrompt = buildTargetedMethodPrompt(targetFile, methodInfo, i + 1);

                // 流式执行（带重试）
                ConsoleStreamingHandler handler = new ConsoleStreamingHandler();
                methodExecutor.runStream(methodPrompt, handler);

                try {
                    handler.await(5, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    currentMethodStats.complete("INTERRUPTED", methodInfo.lineCoverage);
                    break;
                }

                String content = handler.getContent();
                currentMethodStats.incrementIteration();

                // 检查是否有错误
                if (handler.getError() != null) {
                    Throwable error = handler.getError();
                    log.error("❌ LLM call failed for method {}: {}", methodInfo.methodName, error.getMessage());
                    if (error.getCause() != null) {
                        log.error("   Caused by: {}", error.getCause().getMessage());
                    }

                    methodRetryCount++;
                    log.warn("⏳ Retrying... attempt {}/{}", methodRetryCount + 1, maxMethodRetries);

                    if (methodRetryCount >= maxMethodRetries) {
                        log.error("❌ Max retries reached for method {}", methodInfo.methodName);
                        currentMethodStats.complete("FAILED", methodInfo.lineCoverage);
                        methodCompleted = true;
                    } else {
                        // 等待一会再重试
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    continue;
                }

                // 检查响应是否为空
                if (content == null || content.trim().isEmpty()) {
                    log.warn("⚠️ Empty response for method {}, attempt {}/{}",
                            methodInfo.methodName, methodRetryCount + 1, maxMethodRetries);
                    methodRetryCount++;

                    if (methodRetryCount >= maxMethodRetries) {
                        log.error("❌ Max retries reached (empty responses) for method {}", methodInfo.methodName);
                        currentMethodStats.complete("FAILED", methodInfo.lineCoverage);
                        methodCompleted = true;
                    } else {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    continue;
                }

                // 解析最终覆盖率 - 先从 LLM 响应中提取
                double finalCoverage = extractCoverage(content);

                // 如果没有从响应中获取到，直接调用工具获取实际覆盖率
                if (finalCoverage <= 0) {
                    finalCoverage = getActualMethodCoverage(projectRoot, targetFile, methodInfo.methodName);
                }

                // 如果仍然获取不到，使用初始值
                if (finalCoverage <= 0) {
                    finalCoverage = methodInfo.lineCoverage;
                }

                // 判断结果
                String contentLower = content.toLowerCase();
                if (contentLower.contains("failed") && !contentLower.contains("mutation")) {
                    log.warn("❌ Method {} test generation failed", methodInfo.methodName);
                    methodRetryCount++;

                    if (methodRetryCount >= maxMethodRetries) {
                        currentMethodStats.complete("FAILED", finalCoverage);
                        methodCompleted = true;
                    }
                } else {
                    log.info("✅ Method {} completed with coverage: {}%",
                            methodInfo.methodName, String.format("%.1f", finalCoverage));
                    currentMethodStats.complete("SUCCESS", finalCoverage);
                    methodCompleted = true;
                }
            }
        }

        // ===== Phase 3: 汇总 =====
        log.info(">>> Phase 3: Summary");
        log.info("📊 Processed: {}, Skipped: {}, Total: {}",
                processedCount, skippedCount, methodsToProcess.size());

        AgentExecutor summaryExecutor = createExecutor(systemPrompt, 5);
        AgentResult summaryResult = summaryExecutor.run(
                "Call getIterationProgress() to show the final summary of all tested methods.");

        if (summaryResult.success()) {
            log.info("Iterative test generation completed");
        }

        // ===== 生成报告 =====
        String agentDir = getAgentRunDirectory();
        generateReport(agentDir);
    }

    /**
     * 构建针对特定方法的提示词
     */
    private String buildTargetedMethodPrompt(String targetFile, MethodCoverageInfo methodInfo, int iteration) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format("""
                ## ITERATIVE MODE - METHOD #%d: %s

                Target file: %s

                ⚠️ THIS IS A FRESH CONTEXT - Previous conversation is cleared.

                **Current Method Information:**
                - Method Name: `%s`
                - Priority: %s
                - Current Line Coverage: %.1f%%
                - Current Branch Coverage: %.1f%%

                """,
                iteration, methodInfo.methodName,
                targetFile,
                methodInfo.methodName, methodInfo.priority,
                methodInfo.lineCoverage, methodInfo.branchCoverage));

        // 添加覆盖率反馈建议（如果有）
        if (currentPreCheck != null && currentPreCheck.getFeedbackResult() != null) {
            CoverageFeedbackEngine.FeedbackResult feedback = currentPreCheck.getFeedbackResult();
            List<CoverageFeedbackEngine.ImprovementSuggestion> suggestions = feedback.getImprovements();

            // 查找与当前方法相关的建议
            List<CoverageFeedbackEngine.ImprovementSuggestion> methodSuggestions = suggestions.stream()
                    .filter(s -> s.getMethodName() != null && s.getMethodName().contains(methodInfo.methodName))
                    .limit(5)
                    .collect(java.util.stream.Collectors.toList());

            if (!methodSuggestions.isEmpty()) {
                prompt.append("**📊 Feedback Analysis Suggestions:**\n");
                for (CoverageFeedbackEngine.ImprovementSuggestion s : methodSuggestions) {
                    prompt.append(String.format("- [%s] %s\n", s.getPriority(), s.getDescription()));
                }
                prompt.append("\n");
            }

            // 添加边界测试建议
            List<CoverageFeedbackEngine.ImprovementSuggestion> boundarySuggestions = suggestions.stream()
                    .filter(s -> s.getType() == CoverageFeedbackEngine.SuggestionType.BOUNDARY_TEST)
                    .limit(3)
                    .collect(java.util.stream.Collectors.toList());

            if (!boundarySuggestions.isEmpty()) {
                prompt.append("**🎯 Boundary Test Suggestions:**\n");
                for (CoverageFeedbackEngine.ImprovementSuggestion s : boundarySuggestions) {
                    prompt.append(String.format("- %s\n", s.getDescription()));
                }
                prompt.append("\n");
            }
        }

        prompt.append(String.format("""
                **Your Task:**
                1. Read the current test file (readFile)
                2. Analyze the source code for method `%s`
                3. Generate tests to improve coverage for THIS METHOD ONLY
                4. Append tests using writeFileFromLine (do NOT overwrite existing tests)
                5. checkSyntax → compileProject → executeTest
                6. getSingleMethodCoverage to verify improvement
                7. completeCurrentMethod with status and final coverage

                Focus on:
                - Uncovered branches and edge cases
                - Boundary conditions
                - Error handling paths

                After completing, STOP.
                """, methodInfo.methodName));

        return prompt.toString();
    }

    /**
     * 回退到 LLM 驱动的迭代模式（当没有覆盖率数据时）
     */
    private void runIterativeFallback(String targetFile, String taskContext,
            String systemPrompt, String projectRoot) {
        log.info("Using LLM-driven iteration (no coverage data available)");

        int maxMethodIterations = 20;
        int methodRetryCount = 0;
        final int maxMethodRetries = 3;

        for (int i = 1; i <= maxMethodIterations; i++) {
            log.info(">>> Phase 2: Method Iteration #{}", i);

            AgentExecutor methodExecutor = createExecutor(systemPrompt, 10);

            IterationStats.MethodStats currentMethodStats = iterationStats.startMethod("method_" + i, "P1");

            methodExecutor.setTokenStatsCallback((prompt, response) -> {
                currentMethodStats.addPromptTokens(prompt);
                currentMethodStats.addResponseTokens(response);
            });

            String methodPrompt = buildIterativeMethodPrompt(targetFile, i);

            ConsoleStreamingHandler handler = new ConsoleStreamingHandler();
            methodExecutor.runStream(methodPrompt, handler);

            try {
                handler.await(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                currentMethodStats.complete("INTERRUPTED", 0);
                break;
            }

            String content = handler.getContent();

            // 从 LLM 响应中提取实际方法名
            String extractedMethod = extractMethodName(content);
            if (extractedMethod != null) {
                currentMethodStats.setMethodName(extractedMethod);
            }

            double coverage = extractCoverage(content);
            currentMethodStats.incrementIteration();

            // 主动查询实际覆盖率
            double actualCoverage = getActualMethodCoverage(projectRoot, targetFile,
                    currentMethodStats.getMethodName());
            if (actualCoverage > 0) {
                coverage = actualCoverage;
                log.info("📊 Actual coverage verified for {}: {}%", currentMethodStats.getMethodName(), coverage);
            }

            // 检查覆盖率是否达标
            int coverageThreshold = config.getWorkflow() != null ? config.getWorkflow().getCoverageThreshold() : 80;
            boolean coverageMet = actualCoverage >= coverageThreshold;

            String contentLower = content.toLowerCase();
            // 增强的终止检测逻辑，防止死循环
            boolean isComplete = contentLower.contains("iteration_complete") ||
                    contentLower.contains("iteration complete") ||
                    contentLower.contains("all methods completed") ||
                    contentLower.contains("all methods tested") ||
                    // 匹配 "The iterative testing process has been completed successfully"
                    (contentLower.contains("completed") && contentLower.contains("successfully")
                            && contentLower.contains("iterative"));

            // 如果覆盖率达标，也认为任务完成
            if (coverageMet) {
                log.info(">>> Coverage target met ({}% >= {}%) for {}, marking as complete",
                        actualCoverage, coverageThreshold, currentMethodStats.getMethodName());
                isComplete = true;
            }

            if (isComplete) {
                log.info(">>> Iteration completed after {} methods (Termination signal detected)", i - 1);
                iterationStats.getMethodStatsList().remove(currentMethodStats);
                break;
            } else if (handler.getError() != null || contentLower.contains("failed")) {
                methodRetryCount++;
                if (methodRetryCount >= maxMethodRetries) {
                    currentMethodStats.complete("FAILED", coverage);
                    methodRetryCount = 0;
                } else {
                    i--;
                }
            } else {
                currentMethodStats.complete("SUCCESS", coverage);
                methodRetryCount = 0;
            }
        }

        // 生成报告
        String agentDir = getAgentRunDirectory();
        generateReport(agentDir);
    }

    /**
     * 获取 agent 运行目录
     */
    private String getAgentRunDirectory() {
        try {
            // 尝试获取 JAR 文件所在目录
            String jarPath = getClass().getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            Path jarDir = Paths.get(jarPath).getParent();
            if (jarDir != null) {
                return jarDir.toString();
            }
        } catch (Exception e) {
            log.debug("Unable to determine JAR directory: {}", e.getMessage());
        }
        // 回退到当前工作目录
        return System.getProperty("user.dir");
    }

    /**
     * 生成统计报告
     */
    private void generateReport(String projectRoot) {
        if (iterationStats == null) {
            return;
        }

        // 打印控制台摘要
        System.out.println("\n" + "=".repeat(60));
        System.out.println("📊 测试生成统计摘要");
        System.out.println("=".repeat(60));

        List<IterationStats.MethodStats> methods = iterationStats.getMethodStatsList();
        int totalMethods = methods.size();
        long successCount = methods.stream().filter(IterationStats.MethodStats::isSuccess).count();

        System.out.printf("总方法数: %d%n", totalMethods);
        System.out.printf("成功: %d, 失败: %d%n", successCount, totalMethods - successCount);
        System.out.printf("总 Token 使用: %,d (Prompt: %,d, Response: %,d)%n",
                iterationStats.getTotalPromptTokens() + iterationStats.getTotalResponseTokens(),
                iterationStats.getTotalPromptTokens(),
                iterationStats.getTotalResponseTokens());

        if (totalMethods > 0) {
            System.out.printf("平均每方法 Token: %,d%n",
                    (iterationStats.getTotalPromptTokens() + iterationStats.getTotalResponseTokens()) / totalMethods);
        }

        // Token 趋势分析
        long skippedCount = methods.stream().filter(IterationStats.MethodStats::isSkipped).count();
        long processedCount = totalMethods - skippedCount;

        if (skippedCount > 0) {
            System.out.printf("⏭️ 跳过方法: %d (覆盖率已达标)%n", skippedCount);
        }

        if (processedCount >= 3) {
            // 只统计实际处理的方法（非跳过）
            List<IterationStats.MethodStats> processedMethods = methods.stream()
                    .filter(m -> !m.isSkipped())
                    .collect(java.util.stream.Collectors.toList());

            int firstThreeCount = Math.min(3, processedMethods.size());
            int firstThreeSum = processedMethods.subList(0, firstThreeCount).stream()
                    .mapToInt(m -> m.getPromptTokens())
                    .sum();
            int lastThreeSum = processedMethods
                    .subList(Math.max(0, processedMethods.size() - 3), processedMethods.size()).stream()
                    .mapToInt(m -> m.getPromptTokens())
                    .sum();

            if (firstThreeCount > 0 && firstThreeSum > 0) {
                int firstThreeAvg = firstThreeSum / firstThreeCount;
                int lastThreeAvg = lastThreeSum / firstThreeCount;

                if (lastThreeAvg < firstThreeAvg && firstThreeAvg > 0) {
                    int reduction = (firstThreeAvg - lastThreeAvg) * 100 / firstThreeAvg;
                    System.out.printf("✅ Token 下降趋势: 后期比前期减少 %d%%%n", reduction);
                } else {
                    System.out.println("ℹ️ Token 使用保持稳定");
                }
            }
        } else if (processedCount > 0) {
            System.out.printf("ℹ️ 实际处理 %d 个方法 (需 ≥3 个方法才能分析 Token 趋势)%n", processedCount);
        } else if (skippedCount == totalMethods) {
            System.out.println("✅ 所有方法覆盖率已达标，无需生成新测试");
        }

        // 输出覆盖率反馈历史
        if (feedbackEngine != null) {
            String feedbackSummary = feedbackEngine.getIterationSummary();
            if (!feedbackSummary.startsWith("No feedback")) {
                System.out.println("\n📈 覆盖率反馈历史:");
                System.out.println(feedbackSummary);
            }
        }

        System.out.println("=".repeat(60));

        // 保存 Markdown 报告到 result 目录
        if (projectRoot != null) {
            Path resultDir = Paths.get(projectRoot, "result");
            try {
                Files.createDirectories(resultDir);

                // 添加反馈历史到统计
                if (feedbackEngine != null) {
                    iterationStats.setFeedbackSummary(feedbackEngine.getIterationSummary());
                }

                iterationStats.saveReport(resultDir);
            } catch (IOException e) {
                log.error("Failed to create result directory: {}", e.getMessage());
                // 回退到项目根目录
                iterationStats.saveReport(Paths.get(projectRoot));
            }
        }
    }

    /**
     * 从输出中提取方法名
     */
    private String extractMethodName(String content) {
        // 匹配 "Method: xxx" 或 "Testing: xxx" 模式
        Pattern pattern = Pattern.compile("(?:Method|Testing|method_name)[:\\s]+([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 从输出中提取覆盖率
     */
    private double extractCoverage(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }

        // 按优先级尝试多种匹配模式
        String[] patterns = {
                // 工具输出格式: "coverage=100.0" 或 "coverage: 100.0"
                "coverage[=:]\\s*([0-9]+\\.?[0-9]*)%?",
                // Final Coverage 格式: "**Final Coverage:** 100%"
                "Final\\s+Coverage[:\\*\\s]+([0-9]+\\.?[0-9]*)%",
                // line coverage 格式: "line=100.0%" 或 "Line: 100%"
                "line[=:\\s]+([0-9]+\\.?[0-9]*)%",
                // 通用 Coverage 格式: "Coverage: 85.5%"
                "Coverage[:\\s]+([0-9]+\\.?[0-9]*)%?",
                // 简单百分比: "100% coverage" 或 "100% line"
                "([0-9]+\\.?[0-9]*)%\\s*(?:coverage|line)"
        };

        for (String patternStr : patterns) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                try {
                    double value = Double.parseDouble(matcher.group(1));
                    if (value > 0 && value <= 100) {
                        log.debug("Extracted coverage {} from pattern: {}", value, patternStr);
                        return value;
                    }
                } catch (NumberFormatException e) {
                    // 继续尝试下一个模式
                }
            }
        }

        return 0;
    }

    /**
     * 直接调用工具获取方法的实际覆盖率
     */
    private double getActualMethodCoverage(String projectRoot, String targetFile, String methodName) {
        try {
            String className = extractClassName(targetFile);
            Map<String, Object> args = new HashMap<>();
            args.put("modulePath", projectRoot);
            args.put("className", className);
            args.put("methodName", methodName);

            String result = toolRegistry.invoke("getSingleMethodCoverage", args);
            if (result != null && !result.startsWith("ERROR")) {
                // 解析结果格式: "methodName line=XX.X%"
                Pattern pattern = Pattern.compile("line[=:]\\s*([0-9]+\\.?[0-9]*)%");
                Matcher matcher = pattern.matcher(result);
                if (matcher.find()) {
                    double coverage = Double.parseDouble(matcher.group(1));
                    log.info("📊 Actual coverage for {}: {}%", methodName, String.format("%.1f", coverage));
                    return coverage;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get actual coverage for {}: {}", methodName, e.getMessage());
        }
        return 0;
    }

    /**
     * 从输出中提取优先级
     */
    private String extractPriority(String content) {
        if (content.contains("P0"))
            return "P0";
        if (content.contains("P2"))
            return "P2";
        return "P1";
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

        // 添加预检查结果信息
        if (currentPreCheck != null) {
            if (currentPreCheck.hasExistingTests) {
                message.append("\n\n## Pre-check Results\n");
                message.append("✅ Project compiled successfully\n");
                message.append("✅ Existing test file found\n");

                if (currentPreCheck.coverageInfo != null && !currentPreCheck.coverageInfo.isEmpty()) {
                    message.append("\n### Current Coverage Analysis:\n");
                    message.append("```\n");
                    message.append(currentPreCheck.coverageInfo);
                    message.append("\n```\n");
                    message.append("\n### ⚠️ CRITICAL INSTRUCTIONS for Existing Tests:\n");
                    message.append("1. **READ the coverage report above** - it shows which methods need tests\n");
                    message.append(
                            "2. **Symbol meanings**: ✗ = No coverage (MUST TEST), ◐ = Partial (NEED MORE), ✓ = Good (SKIP)\n");
                    message.append("3. **DO NOT duplicate existing tests** - Read existing test file first\n");
                    message.append("4. **Focus on uncovered code paths**:\n");
                    message.append("   - Methods marked ✗ (0% coverage): Create new test methods\n");
                    message.append("   - Methods marked ◐ (partial): Add tests for uncovered branches\n");
                    message.append("   - Methods marked ✓ (≥80%): SKIP - already well tested\n");
                    message.append("5. **Use `writeFileFromLine` to APPEND tests**, do not overwrite existing tests\n");
                }
            } else {
                message.append("\n\n## Pre-check Results\n");
                message.append("✅ Project compiled successfully\n");
                message.append("ℹ️ No existing test file - will create new tests for ALL methods\n");
            }
        }

        return message.toString();
    }

    /**
     * 构建迭代初始化提示
     */
    private String buildIterativeInitPrompt(String targetFile) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("""
                ## ITERATIVE MODE - PHASE 1: INITIALIZATION

                Target file: %s

                """, targetFile));

        // 添加预检查结果
        if (currentPreCheck != null) {
            sb.append("## Pre-check Results (Already completed)\n");
            sb.append("✅ Project compiled successfully\n");

            if (currentPreCheck.hasExistingTests) {
                sb.append("✅ Existing test file found\n");

                if (currentPreCheck.coverageInfo != null && !currentPreCheck.coverageInfo.isEmpty()) {
                    sb.append("\n### Current Coverage Analysis:\n");
                    sb.append("```\n");
                    sb.append(currentPreCheck.coverageInfo);
                    sb.append("\n```\n\n");
                    sb.append("### ⚠️ COVERAGE-DRIVEN TEST GENERATION (MANDATORY):\n\n");
                    sb.append("**Symbol meanings in coverage report:**\n");
                    sb.append("- ✗ = 0% coverage → MUST generate tests\n");
                    sb.append("- ◐ = Partial coverage → ADD tests for uncovered branches\n");
                    sb.append("- ✓ = ≥80% coverage → SKIP (already covered)\n\n");
                    sb.append("**Your task:**\n");
                    sb.append("1. When calling `initMethodIteration`, ONLY include methods with ✗ or ◐\n");
                    sb.append("2. For each uncovered method:\n");
                    sb.append("   - Read source code to understand the logic\n");
                    sb.append("   - Identify uncovered branches/paths\n");
                    sb.append("   - Generate tests targeting those specific paths\n");
                    sb.append("3. Use `writeFileFromLine` to APPEND tests, do not overwrite\n");
                    sb.append("4. After each method, verify coverage improved\n\n");
                }
            } else {
                sb.append("ℹ️ No existing test file - will create new tests for ALL methods\n\n");
            }
        }

        sb.append("""
                Please complete these steps:
                1. Check if test directory exists (directoryExists)
                2. Check if test file exists (fileExists)
                3. Read the source file (readFile)
                4. Analyze method priorities (getPriorityMethods)
                5. Initialize iteration (initMethodIteration)
                6. Create the test file skeleton if it doesn't exist (writeFile)

                After initialization, call getNextMethod() to get the first method.
                Then STOP and wait for next instruction.
                """);

        return sb.toString();
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

        // 先转换为绝对路径
        File file = new File(targetFile);
        if (!file.isAbsolute()) {
            file = file.getAbsoluteFile();
        }

        String normalized = file.getPath().replace("\\", "/");

        // 方法1: 查找 /src/main/java/ 或 /src/ 目录
        int srcMainIndex = normalized.indexOf("/src/main/java/");
        if (srcMainIndex > 0) {
            return normalized.substring(0, srcMainIndex);
        }

        int srcIndex = normalized.indexOf("/src/");
        if (srcIndex > 0) {
            return normalized.substring(0, srcIndex);
        }

        // 方法2: 如果找不到 src 目录，向上查找 pom.xml（回退逻辑）
        File current = file.isDirectory() ? file : file.getParentFile();
        while (current != null) {
            File pomFile = new File(current, "pom.xml");
            if (pomFile.exists()) {
                return current.getAbsolutePath();
            }
            current = current.getParentFile();
        }

        // 方法3: 如果都找不到，返回文件所在目录的父目录（至少保证有路径）
        File parent = file.getParentFile();
        if (parent != null) {
            return parent.getAbsolutePath();
        }

        return null;
    }

    /**
     * 测试 LLM 连接
     */
    public boolean testLlmConnection() {
        return LlmAdapterFactory.testConnection(llmAdapter);
    }

    /**
     * 方法覆盖率信息
     */
    public static class MethodCoverageInfo {
        String methodName;
        String priority;
        double lineCoverage;
        double branchCoverage;
        boolean needsTest; // 是否需要生成测试

        public MethodCoverageInfo(String methodName, String priority, double lineCoverage, double branchCoverage) {
            this.methodName = methodName;
            this.priority = priority;
            this.lineCoverage = lineCoverage;
            this.branchCoverage = branchCoverage;
        }

        public double getOverallCoverage() {
            // 综合覆盖率：行覆盖和分支覆盖的加权平均
            return (lineCoverage + branchCoverage) / 2;
        }

        @Override
        public String toString() {
            return String.format("%s [%s] - Line: %.1f%%, Branch: %.1f%%",
                    methodName, priority, lineCoverage, branchCoverage);
        }
    }

    /**
     * 预检查结果
     */
    private static class PreCheckResult {
        boolean success;
        String errorMessage;
        String coverageInfo; // 覆盖率信息文本，传递给 LLM
        boolean hasExistingTests;
        List<MethodCoverageInfo> methodCoverages; // 每个方法的覆盖率
        CoverageFeedbackEngine.FeedbackResult feedbackResult; // 覆盖率反馈分析结果

        static PreCheckResult success(String coverageInfo, boolean hasExistingTests,
                List<MethodCoverageInfo> methodCoverages) {
            PreCheckResult r = new PreCheckResult();
            r.success = true;
            r.coverageInfo = coverageInfo;
            r.hasExistingTests = hasExistingTests;
            r.methodCoverages = methodCoverages != null ? methodCoverages : new ArrayList<>();
            return r;
        }

        static PreCheckResult failure(String error) {
            PreCheckResult r = new PreCheckResult();
            r.success = false;
            r.errorMessage = error;
            r.methodCoverages = new ArrayList<>();
            return r;
        }

        /**
         * 获取按覆盖率排序的方法列表（覆盖率低的在前）
         */
        List<MethodCoverageInfo> getMethodsSortedByCoverage() {
            if (methodCoverages == null || methodCoverages.isEmpty()) {
                return new ArrayList<>();
            }
            return methodCoverages.stream()
                    .sorted((a, b) -> Double.compare(a.getOverallCoverage(), b.getOverallCoverage()))
                    .collect(java.util.stream.Collectors.toList());
        }

        void setFeedbackResult(CoverageFeedbackEngine.FeedbackResult result) {
            this.feedbackResult = result;
        }

        CoverageFeedbackEngine.FeedbackResult getFeedbackResult() {
            return feedbackResult;
        }
    }

    /**
     * 执行预检查：编译工程和覆盖率分析
     */
    private PreCheckResult performPreCheck(String projectRoot, String targetFile) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🔍 Pre-check Phase: Validating project environment");
        System.out.println("=".repeat(60));

        if (projectRoot == null) {
            return PreCheckResult.failure("Cannot determine project root from target file: " + targetFile);
        }

        // Step 1: 检查测试文件是否存在
        System.out.println("\n📄 Step 1: Checking for existing test file...");
        String testFilePath = calculateTestFilePath(targetFile);
        boolean hasExistingTests = Files.exists(Paths.get(testFilePath));

        boolean skipTestExecution = false;

        if (hasExistingTests) {
            System.out.println("✅ Found existing test file: " + testFilePath);
        } else {
            System.out.println("ℹ️ No existing test file found. Will compile and create new tests.");
            // 没有测试文件时，只编译不执行测试
            try {
                Map<String, Object> emptyArgs = new HashMap<>();
                String compileResult = toolRegistry.invoke("compileProject", emptyArgs);
                if (compileResult.contains("ERROR") || compileResult.contains("exitCode=1")) {
                    System.err.println("❌ Compilation failed!");
                    return PreCheckResult.failure("Compilation failed:\n" + compileResult);
                }
                System.out.println("✅ Compilation successful");
                // 标记跳过测试执行，但继续后续的覆盖率分析（静态分析）
                skipTestExecution = true;
            } catch (Exception e) {
                log.error("Failed to compile project", e);
                return PreCheckResult.failure("Compilation error: " + e.getMessage());
            }
        }

        // Step 2: 清理并执行测试，生成最新覆盖率数据
        if (!skipTestExecution) {
            System.out.println("\n🧪 Step 2: Running 'clean test' to generate fresh coverage data...");
            try {
                Map<String, Object> emptyArgs = new HashMap<>();
                String testResult = toolRegistry.invoke("cleanAndTest", emptyArgs);

                if (testResult.contains("exitCode=0") || testResult.contains("\"exitCode\":0")) {
                    System.out.println("✅ Clean and test completed successfully");
                } else if (testResult.contains("ERROR") || testResult.contains("exitCode=1")) {
                    System.out.println("⚠️ Some tests may have failed, continuing with coverage analysis...");
                } else {
                    System.out.println("✅ Test execution completed");
                }
            } catch (Exception e) {
                log.warn("Failed to execute tests: {}", e.getMessage());
                System.out.println("⚠️ Could not run tests: " + e.getMessage());
            }
        } else {
            System.out.println("\n🧪 Step 2: Skipping test execution (no existing tests)");
        }

        // Step 3: 获取覆盖率报告
        System.out.println("\n📊 Step 3: Analyzing coverage...");
        String coverageInfo = null;
        String uncoveredMethods = null;
        List<MethodCoverageInfo> methodCoverages = new ArrayList<>();

        try {
            String className = extractClassName(targetFile);
            int threshold = config.getWorkflow() != null ? config.getWorkflow().getCoverageThreshold() : 80;

            // 获取详细覆盖率
            Map<String, Object> coverageArgs = new HashMap<>();
            coverageArgs.put("modulePath", projectRoot);
            coverageArgs.put("className", className);
            coverageInfo = toolRegistry.invoke("getMethodCoverageDetails", coverageArgs);

            // 获取未覆盖方法列表
            Map<String, Object> uncoveredArgs = new HashMap<>();
            uncoveredArgs.put("modulePath", projectRoot);
            uncoveredArgs.put("className", className);
            uncoveredArgs.put("threshold", threshold);
            uncoveredMethods = toolRegistry.invoke("getUncoveredMethods", uncoveredArgs);

            if (coverageInfo != null && !coverageInfo.startsWith("ERROR")) {
                System.out.println("✅ Coverage analysis complete:");
                // 打印简要摘要
                String[] lines = coverageInfo.split("\n");
                for (int i = 0; i < Math.min(15, lines.length); i++) {
                    System.out.println("   " + lines[i]);
                }
                if (lines.length > 15) {
                    System.out.println("   ... (" + (lines.length - 15) + " more lines)");
                }

                // 解析覆盖率数据，创建 MethodCoverageInfo 列表
                methodCoverages = parseCoverageInfo(coverageInfo, threshold);

                // 合并覆盖率信息
                if (uncoveredMethods != null && !uncoveredMethods.startsWith("ERROR")) {
                    coverageInfo = coverageInfo + "\n\n" + uncoveredMethods;
                }
            } else {
                System.out.println("⚠️ Could not get coverage details (no JaCoCo report found)");

                // 尝试静态分析获取方法列表（作为回退机制）
                try {
                    System.out.println("ℹ️ Attempting static analysis to discover methods...");
                    Map<String, Object> analyzeArgs = new HashMap<>();
                    analyzeArgs.put("filePath", targetFile);
                    // 注意：CodeAnalyzerTool 需要有获取方法列表的功能，这里假设使用 analyzeCode
                    String analysisResult = toolRegistry.invoke("analyzeCode", analyzeArgs);

                    if (analysisResult != null && !analysisResult.startsWith("ERROR")) {
                        // 解析 AST 分析结果，提取方法名
                        // 这里做一个简单的模拟实现，实际应该解析 JSON 或结构化输出
                        // 假设 analyzeCode 返回了包含方法签名的文本
                        List<String> methodNames = extractMethodNamesFromAnalysis(analysisResult);

                        if (!methodNames.isEmpty()) {
                            System.out.println("✅ Discovered " + methodNames.size() + " methods via static analysis");
                            StringBuilder sb = new StringBuilder("Static Analysis Result (No coverage data yet):\n");
                            for (String method : methodNames) {
                                // 构造默认的 0% 覆盖率信息
                                methodCoverages.add(new MethodCoverageInfo(method, "P0", 0.0, 0.0));
                                sb.append(String.format("✗ %s Line: 0.0%% Branch: 0.0%%\n", method));
                            }
                            coverageInfo = sb.toString();
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Static analysis fallback failed", ex);
                }

                if (coverageInfo == null) {
                    coverageInfo = null; // 保持 null 以触发 Fallback 模式
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get coverage: {}", e.getMessage());
            System.out.println("⚠️ Could not analyze coverage: " + e.getMessage());
        }

        // Step 4: 运行覆盖率反馈分析（如果可用）
        CoverageFeedbackEngine.FeedbackResult feedbackResult = null;
        if (feedbackEngine != null && coverageInfo != null) {
            System.out.println("\n🔬 Step 4: Running coverage feedback analysis...");
            try {
                String className = extractClassName(targetFile);
                int threshold = config.getWorkflow() != null ? config.getWorkflow().getCoverageThreshold() : 80;
                feedbackResult = feedbackEngine.runFeedbackCycle(projectRoot, className, threshold);

                if (feedbackResult != null) {
                    System.out.println("✅ Feedback analysis complete:");
                    System.out.println("   Current coverage: " + feedbackResult.getCurrentCoverage() + "%");
                    System.out.println("   Target: " + feedbackResult.getTargetCoverage() + "%");
                    System.out.println("   Status: " + (feedbackResult.isTargetMet() ? "✓ TARGET MET" : "✗ NOT MET"));

                    if (!feedbackResult.isTargetMet()) {
                        List<CoverageFeedbackEngine.ImprovementSuggestion> improvements = feedbackResult
                                .getImprovements();
                        if (!improvements.isEmpty()) {
                            System.out.println("   Improvement suggestions:");
                            for (int i = 0; i < Math.min(5, improvements.size()); i++) {
                                CoverageFeedbackEngine.ImprovementSuggestion s = improvements.get(i);
                                System.out.printf("   - [%s] %s: %s%n", s.getPriority(), s.getType(),
                                        s.getDescription());
                            }
                            if (improvements.size() > 5) {
                                System.out.println("   ... and " + (improvements.size() - 5) + " more suggestions");
                            }
                        }
                        System.out.println("   Recommended action: " + feedbackResult.getNextAction());
                    }
                }
            } catch (Exception e) {
                log.warn("Feedback analysis failed: {}", e.getMessage());
                System.out.println("⚠️ Feedback analysis failed: " + e.getMessage());
            }
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("✅ Pre-check completed. Starting test generation...");
        System.out.println("=".repeat(60) + "\n");

        PreCheckResult result = PreCheckResult.success(coverageInfo, hasExistingTests, methodCoverages);
        result.setFeedbackResult(feedbackResult);
        return result;
    }

    /**
     * 解析覆盖率信息，提取每个方法的覆盖率
     */
    private List<MethodCoverageInfo> parseCoverageInfo(String coverageInfo, int threshold) {
        List<MethodCoverageInfo> methods = new ArrayList<>();
        if (coverageInfo == null || coverageInfo.isEmpty()) {
            return methods;
        }

        // 解析格式: ✗ methodName(params) Line: 0.0% Branch: 0.0%
        // 或: ◐ methodName(params) Line: 50.0% Branch: 25.0%
        // 或: ✓ methodName(params) Line: 100.0% Branch: 100.0%
        Pattern pattern = Pattern
                .compile("([✓◐✗])\\s+(\\w+)\\([^)]*\\)\\s+Line:\\s*([\\d.]+)%\\s+Branch:\\s*([\\d.]+)%");
        Matcher matcher = pattern.matcher(coverageInfo);

        while (matcher.find()) {
            // status = matcher.group(1); // ✓, ◐, ✗ - 不使用，直接根据覆盖率判断
            String methodName = matcher.group(2);
            double lineCoverage = Double.parseDouble(matcher.group(3));
            double branchCoverage = Double.parseDouble(matcher.group(4));

            // 跳过构造方法
            if ("constructor".equals(methodName)) {
                continue;
            }

            // 确定优先级：覆盖率低的优先级高
            String priority;
            if (lineCoverage == 0) {
                priority = "P0"; // 无覆盖，最高优先级
            } else if (lineCoverage < threshold) {
                priority = "P1"; // 部分覆盖
            } else {
                priority = "P2"; // 已达到覆盖率要求
            }

            MethodCoverageInfo info = new MethodCoverageInfo(methodName, priority, lineCoverage, branchCoverage);
            info.needsTest = lineCoverage < threshold;
            methods.add(info);
        }

        // 按覆盖率排序（低的在前）
        methods.sort((a, b) -> Double.compare(a.getOverallCoverage(), b.getOverallCoverage()));

        log.info("Parsed {} methods from coverage info", methods.size());
        for (MethodCoverageInfo m : methods) {
            log.info("  - {}", m);
        }

        return methods;
    }

    /**
     * 从分析结果中提取方法名
     * 这是一个简单的辅助方法，用于解析 analyzeCode 的输出
     */
    private List<String> extractMethodNamesFromAnalysis(String analysisResult) {
        List<String> methods = new ArrayList<>();
        // 匹配常见的 AST 输出格式，例如 "Method: methodName(args)" 或 "- methodName"
        // 增加对 " - " 前缀的匹配，并处理可能的参数列表
        Pattern pattern = Pattern.compile("(?:Method:|\\s*-\\s+)([a-zA-Z_][a-zA-Z0-9_]*)(?:\\(.*\\))?");
        Matcher matcher = pattern.matcher(analysisResult);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!methods.contains(name) && !name.equals("main") && !name.equals("toString")
                    && !name.equals("hashCode")) {
                methods.add(name);
            }
        }

        // 如果上面的正则没匹配到，尝试匹配 "Method: methodName" (没有参数列表的情况)
        Pattern simplePattern = Pattern.compile("Method:\\s*([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher simpleMatcher = simplePattern.matcher(analysisResult);
        while (simpleMatcher.find()) {
            String name = simpleMatcher.group(1);
            if (!methods.contains(name) && !name.equals("main") && !name.equals("toString")
                    && !name.equals("hashCode")) {
                methods.add(name);
            }
        }

        return methods;
    }

    /**
     * 计算测试文件路径
     */
    private String calculateTestFilePath(String sourceFile) {
        // 1. 转换为绝对路径
        Path absPath = Paths.get(sourceFile).toAbsolutePath();
        String normalizedPath = absPath.toString().replace("\\", "/");

        // 2. 替换 src/main/java 为 src/test/java
        // 使用正则替换，兼容不同的路径前缀
        String testPath = normalizedPath.replaceFirst("/src/main/java/", "/src/test/java/");

        // 如果没有替换成功（可能路径结构不同），尝试不带前导斜杠的替换
        if (testPath.equals(normalizedPath)) {
            testPath = normalizedPath.replaceFirst("src/main/java/", "src/test/java/");
        }

        // 3. 替换后缀
        return testPath.replace(".java", "Test.java");
    }

    /**
     * 提取全限定类名
     */
    private String extractClassName(String sourceFile) {
        // 1. 尝试从路径解析 (快速路径)
        String normalized = sourceFile.replace("\\", "/");
        int srcMainIndex = normalized.indexOf("/src/main/java/");
        if (srcMainIndex >= 0) {
            String className = normalized.substring(srcMainIndex + "/src/main/java/".length());
            className = className.replace("/", ".").replace(".java", "");
            return className;
        }

        // 2. 尝试解析文件内容 (回退机制)
        try {
            Path path = Paths.get(sourceFile);
            if (Files.exists(path)) {
                CompilationUnit cu = StaticJavaParser.parse(path);

                // 获取包名
                String packageName = cu.getPackageDeclaration()
                        .map(pd -> pd.getNameAsString())
                        .orElse("");

                // 获取主类名
                String simpleClassName = null;
                List<TypeDeclaration<?>> types = cu.getTypes();

                if (types.isEmpty()) {
                    log.warn("No types found in file: {}", sourceFile);
                    return null;
                }

                // 策略1: 优先查找 public 类
                for (TypeDeclaration<?> type : types) {
                    if (type.isPublic()) {
                        simpleClassName = type.getNameAsString();
                        break;
                    }
                }

                // 策略2: 查找与文件名匹配的类 (如果是相对路径，取文件名)
                if (simpleClassName == null) {
                    String fileName = path.getFileName().toString().replace(".java", "");
                    for (TypeDeclaration<?> type : types) {
                        if (type.getNameAsString().equals(fileName)) {
                            simpleClassName = type.getNameAsString();
                            break;
                        }
                    }
                }

                // 策略3: 取第一个类
                if (simpleClassName == null) {
                    simpleClassName = types.get(0).getNameAsString();
                    log.info("Using first found type '{}' as main class for {}", simpleClassName, sourceFile);
                }

                if (!packageName.isEmpty()) {
                    return packageName + "." + simpleClassName;
                } else {
                    return simpleClassName;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse source file to extract class name: {}", e.getMessage());
        }

        return null;
    }
}
