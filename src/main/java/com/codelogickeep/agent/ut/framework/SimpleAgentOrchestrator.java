package com.codelogickeep.agent.ut.framework;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.adapter.LlmAdapter;
import com.codelogickeep.agent.ut.framework.adapter.LlmAdapterFactory;
import com.codelogickeep.agent.ut.framework.executor.AgentExecutor;
import com.codelogickeep.agent.ut.framework.executor.AgentResult;
import com.codelogickeep.agent.ut.framework.executor.ConsoleStreamingHandler;
import com.codelogickeep.agent.ut.framework.model.IterationStats;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    public SimpleAgentOrchestrator(AppConfig config, List<Object> tools) {
        this.config = config;
        this.llmAdapter = LlmAdapterFactory.create(config.getLlm());
        this.toolRegistry = new ToolRegistry();
        this.toolRegistry.registerAll(tools);
        this.maxIterations = config.getWorkflow() != null ? config.getWorkflow().getMaxRetries() * 10 : 50;

        log.info("SimpleAgentOrchestrator initialized with {} tools", toolRegistry.size());
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
     */
    private void runIterative(String targetFile, String taskContext) {
        log.info("Starting Agent (ITERATIVE mode) for: {}", targetFile);

        String projectRoot = extractProjectRoot(targetFile);
        String systemPrompt = loadSystemPrompt(projectRoot);

        // 初始化统计
        iterationStats = new IterationStats(targetFile);

        // ===== Phase 1: 初始化 =====
        log.info(">>> Phase 1: Initialization");

        // 智谱 AI 对消息窗口大小敏感，使用较小的窗口（8 条消息）
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

        // ===== Phase 2: 逐方法迭代 =====
        int maxMethodIterations = 20;
        String currentMethodName = null;
        String currentPriority = "P1";
        int methodRetryCount = 0;
        final int maxMethodRetries = 3;

        for (int i = 1; i <= maxMethodIterations; i++) {
            log.info(">>> Phase 2: Method Iteration #{}", i);

            // 每个方法创建新的执行器（清空上下文！）
            AgentExecutor methodExecutor = createExecutor(systemPrompt, 10);

            // 记录当前方法的统计
            final int methodIndex = i;
            IterationStats.MethodStats currentMethodStats = iterationStats.startMethod("method_" + i, currentPriority);

            methodExecutor.setTokenStatsCallback((prompt, response) -> {
                currentMethodStats.addPromptTokens(prompt);
                currentMethodStats.addResponseTokens(response);
                log.info("📊 Method #{} - Prompt: {} tokens, Response: {} tokens",
                        methodIndex, prompt, response);
            });

            String methodPrompt = buildIterativeMethodPrompt(targetFile, i);

            // 流式执行
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

            // 解析方法名（从输出中提取）
            String extractedMethod = extractMethodName(content);
            if (extractedMethod != null) {
                currentMethodName = extractedMethod;
            }

            // 解析覆盖率
            double coverage = extractCoverage(content);

            // 更新当前方法统计
            currentMethodStats.incrementIteration();

            // 判断结果 - 检查迭代是否完成（忽略大小写）
            String contentLower = content.toLowerCase();
            if (contentLower.contains("iteration_complete") ||
                    contentLower.contains("iteration complete") ||
                    contentLower.contains("all methods completed") ||
                    contentLower.contains("6/6 completed") || // 检测完成比例
                    contentLower.contains("all methods have been")) {
                log.info(">>> Iteration completed after {} methods", i - 1);
                // 移除最后一个未完成的统计（因为它只是检查完成状态）
                iterationStats.getMethodStatsList().remove(currentMethodStats);
                break;
            } else if (handler.getError() != null || contentLower.contains("failed")
                    || contentLower.contains("error")) {
                // 只有明确失败才标记为失败
                log.warn("❌ Method {} failed, attempt {}/{}", currentMethodName, methodRetryCount + 1,
                        maxMethodRetries);
                methodRetryCount++;
                if (methodRetryCount >= maxMethodRetries) {
                    currentMethodStats.complete("FAILED", coverage);
                    methodRetryCount = 0;
                } else {
                    // 不增加 i，重试当前方法
                    i--;
                }
            } else {
                // 默认：没有错误就视为成功
                // 检查是否有覆盖率信息或方法完成的标志
                boolean hasCompletion = contentLower.contains("success") ||
                        contentLower.contains("completecurrentmethod") ||
                        contentLower.contains("completed") ||
                        contentLower.contains("coverage") ||
                        contentLower.contains("getnextmethod");

                if (hasCompletion || coverage > 0) {
                    log.info("✅ Method {} completed with coverage: {}%", currentMethodName,
                            String.format("%.1f", coverage));
                    currentMethodStats.complete("SUCCESS", coverage);
                    currentPriority = extractPriority(content);
                    methodRetryCount = 0;
                } else {
                    // 即使没有明确标志，如果没有错误，也视为成功
                    log.info("✅ Method {} iteration completed", currentMethodName);
                    currentMethodStats.complete("SUCCESS", coverage);
                    methodRetryCount = 0;
                }
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

        // ===== 生成报告 =====
        // 获取 agent 运行目录（JAR 所在目录）
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
        if (methods.size() >= 3) {
            int firstThreeCount = Math.min(3, methods.size());
            int firstThreeSum = methods.subList(0, firstThreeCount).stream()
                    .mapToInt(m -> m.getPromptTokens())
                    .sum();
            int lastThreeSum = methods.subList(Math.max(0, methods.size() - 3), methods.size()).stream()
                    .mapToInt(m -> m.getPromptTokens())
                    .sum();

            // 避免除零
            if (firstThreeCount > 0 && firstThreeSum > 0) {
                int firstThreeAvg = firstThreeSum / firstThreeCount;
                int lastThreeAvg = lastThreeSum / firstThreeCount;

                if (lastThreeAvg < firstThreeAvg && firstThreeAvg > 0) {
                    int reduction = (firstThreeAvg - lastThreeAvg) * 100 / firstThreeAvg;
                    System.out.printf("✅ Token 下降趋势: 后期比前期减少 %d%%%n", reduction);
                } else {
                    System.out.println("ℹ️ Token 使用保持稳定");
                }
            } else {
                System.out.println("ℹ️ Token 统计数据不足");
            }
        }

        System.out.println("=".repeat(60));

        // 保存 Markdown 报告到 result 目录
        if (projectRoot != null) {
            Path resultDir = Paths.get(projectRoot, "result");
            try {
                Files.createDirectories(resultDir);
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
        // 匹配 "coverage: 85.5%" 或 "Coverage: 85.5" 模式
        Pattern pattern = Pattern.compile("(?:coverage|Coverage)[:\\s]+([0-9]+\\.?[0-9]*)%?");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
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
                    message.append("2. **Symbol meanings**: ✗ = No coverage (MUST TEST), ◐ = Partial (NEED MORE), ✓ = Good (SKIP)\n");
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

    /**
     * 预检查结果
     */
    private static class PreCheckResult {
        boolean success;
        String errorMessage;
        String coverageInfo; // 覆盖率信息，传递给 LLM
        boolean hasExistingTests;

        static PreCheckResult success(String coverageInfo, boolean hasExistingTests) {
            PreCheckResult r = new PreCheckResult();
            r.success = true;
            r.coverageInfo = coverageInfo;
            r.hasExistingTests = hasExistingTests;
            return r;
        }

        static PreCheckResult failure(String error) {
            PreCheckResult r = new PreCheckResult();
            r.success = false;
            r.errorMessage = error;
            return r;
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

        // Step 1: 编译工程
        System.out.println("\n📦 Step 1: Compiling project...");
        try {
            Map<String, Object> emptyArgs = new HashMap<>();
            String compileResult = toolRegistry.invoke("compileProject", emptyArgs);
            if (compileResult.contains("exitCode=0") || compileResult.contains("\"exitCode\":0")) {
                System.out.println("✅ Compilation successful");
            } else if (compileResult.contains("ERROR") || compileResult.contains("exitCode=1")) {
                System.err.println("❌ Compilation failed!");
                return PreCheckResult.failure("Compilation failed:\n" + compileResult);
            } else {
                System.out.println("✅ Compilation completed");
            }
        } catch (Exception e) {
            log.error("Failed to compile project", e);
            return PreCheckResult.failure("Compilation error: " + e.getMessage());
        }

        // Step 2: 检查测试文件是否存在
        System.out.println("\n📄 Step 2: Checking for existing test file...");
        String testFilePath = calculateTestFilePath(targetFile);
        boolean hasExistingTests = Files.exists(Paths.get(testFilePath));

        if (hasExistingTests) {
            System.out.println("✅ Found existing test file: " + testFilePath);
        } else {
            System.out.println("ℹ️ No existing test file found. Will create new tests.");
            return PreCheckResult.success(null, false);
        }

        // Step 3: 执行测试并获取覆盖率
        System.out.println("\n🧪 Step 3: Running existing tests and collecting coverage...");
        try {
            String className = extractClassName(targetFile);
            String testClassName = className + "Test";

            Map<String, Object> testArgs = new HashMap<>();
            testArgs.put("testClass", testClassName);
            String testResult = toolRegistry.invoke("executeTest", testArgs);

            if (testResult.contains("exitCode=0") || testResult.contains("\"exitCode\":0")) {
                System.out.println("✅ All existing tests passed");
            } else {
                System.out.println("⚠️ Some tests may have failed, continuing with coverage analysis...");
            }
        } catch (Exception e) {
            log.warn("Failed to execute tests: {}", e.getMessage());
            System.out.println("⚠️ Could not run tests: " + e.getMessage());
        }

        // Step 4: 获取覆盖率报告
        System.out.println("\n📊 Step 4: Analyzing coverage...");
        String coverageInfo = null;
        String uncoveredMethods = null;
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
                
                // 合并覆盖率信息
                if (uncoveredMethods != null && !uncoveredMethods.startsWith("ERROR")) {
                    coverageInfo = coverageInfo + "\n\n" + uncoveredMethods;
                }
            } else {
                System.out.println("⚠️ Could not get coverage details (no JaCoCo report found)");
                coverageInfo = null;
            }
        } catch (Exception e) {
            log.warn("Failed to get coverage: {}", e.getMessage());
            System.out.println("⚠️ Could not analyze coverage: " + e.getMessage());
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("✅ Pre-check completed. Starting test generation...");
        System.out.println("=".repeat(60) + "\n");

        return PreCheckResult.success(coverageInfo, hasExistingTests);
    }

    /**
     * 计算测试文件路径
     */
    private String calculateTestFilePath(String sourceFile) {
        return sourceFile
                .replace("/src/main/java/", "/src/test/java/")
                .replace(".java", "Test.java");
    }

    /**
     * 提取全限定类名
     */
    private String extractClassName(String sourceFile) {
        String normalized = sourceFile.replace("\\", "/");
        int srcMainIndex = normalized.indexOf("/src/main/java/");
        if (srcMainIndex >= 0) {
            String className = normalized.substring(srcMainIndex + "/src/main/java/".length());
            className = className.replace("/", ".").replace(".java", "");
            return className;
        }
        return null;
    }
}
