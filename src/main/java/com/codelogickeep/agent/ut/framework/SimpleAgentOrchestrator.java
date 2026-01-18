package com.codelogickeep.agent.ut.framework;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.engine.CoverageFeedbackEngine;
import com.codelogickeep.agent.ut.framework.adapter.LlmAdapter;
import com.codelogickeep.agent.ut.framework.adapter.LlmAdapterFactory;
import com.codelogickeep.agent.ut.framework.executor.AgentExecutor;
import com.codelogickeep.agent.ut.framework.executor.AgentResult;
import com.codelogickeep.agent.ut.framework.executor.ConsoleStreamingHandler;
import com.codelogickeep.agent.ut.framework.model.IterationStats;
import com.codelogickeep.agent.ut.framework.phase.PhaseManager;
import com.codelogickeep.agent.ut.framework.phase.WorkflowPhase;
import com.codelogickeep.agent.ut.framework.precheck.PreCheckExecutor;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import com.codelogickeep.agent.ut.framework.util.ClassNameExtractor;
import com.codelogickeep.agent.ut.framework.util.PromptTemplateLoader;
import com.codelogickeep.agent.ut.framework.pipeline.FixPromptBuilder;
import com.codelogickeep.agent.ut.framework.pipeline.VerificationPipeline;
import com.codelogickeep.agent.ut.framework.pipeline.VerificationResult;
import com.codelogickeep.agent.ut.framework.pipeline.VerificationStep;
import com.codelogickeep.agent.ut.model.PreCheckResult;
import com.codelogickeep.agent.ut.model.MethodCoverageInfo;
import com.codelogickeep.agent.ut.tools.BoundaryAnalyzerTool;
import com.codelogickeep.agent.ut.tools.CoverageTool;
import com.codelogickeep.agent.ut.tools.MutationTestTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
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
    private final PhaseManager phaseManager;
    private final List<Object> allTools;
    private final PreCheckExecutor preCheckExecutor;
    private final VerificationPipeline verificationPipeline;

    // 迭代统计
    private IterationStats iterationStats;

    // 覆盖率反馈引擎
    private CoverageFeedbackEngine feedbackEngine;

    public SimpleAgentOrchestrator(AppConfig config, List<Object> tools) {
        this.config = config;
        this.allTools = tools;
        this.llmAdapter = LlmAdapterFactory.create(config.getLlm());
        this.toolRegistry = new ToolRegistry();

        // 初始化阶段管理器
        this.phaseManager = new PhaseManager(config, tools);

        // 根据阶段管理器加载工具
        if (phaseManager.isIterativeMode()) {
            // 阶段切换模式：加载当前阶段的工具
            phaseManager.initializeTools(toolRegistry);
        } else {
            // 传统模式：加载所有工具
            this.toolRegistry.registerAll(tools);
        }

        this.maxIterations = config.getWorkflow() != null ? config.getWorkflow().getMaxRetries() * 10 : 50;

        // 初始化覆盖率反馈引擎
        initFeedbackEngine(tools);

        // 初始化 PreCheckExecutor
        this.preCheckExecutor = new PreCheckExecutor(toolRegistry, config, feedbackEngine);

        // 初始化验证管道
        this.verificationPipeline = new VerificationPipeline(toolRegistry, config);

        log.info("SimpleAgentOrchestrator initialized with {} tools, phase switching: {}",
                toolRegistry.size(), phaseManager.isIterativeMode());
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
        if (!currentPreCheck.isSuccess()) {
            log.error("Pre-check failed: {}", currentPreCheck.getErrorMessage());
            System.err.println("\n❌ Pre-check failed: " + currentPreCheck.getErrorMessage());
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
     * 迭代模式 - 每个方法独立上下文 + 自动验证管道
     * 
     * 新流程：
     * 1. LLM 生成测试代码
     * 2. Orchestrator 自动执行验证管道（语法检查 → 编译 → 测试 → 覆盖率）
     * 3. 验证失败时调用 LLM 修复
     * 4. 覆盖率不足时让 LLM 继续生成测试
     */
    private void runIterative(String targetFile, String taskContext) {
        log.info("Starting Agent (ITERATIVE mode with auto-verification) for: {}", targetFile);

        String projectRoot = extractProjectRoot(targetFile);
        String systemPrompt = loadSystemPrompt(projectRoot);
        int coverageThreshold = config.getWorkflow() != null ? config.getWorkflow().getCoverageThreshold() : 80;

        // 计算测试文件路径和类名
        String testFilePath = calculateTestFilePath(targetFile);
        String targetClassName = extractClassName(targetFile);
        String testClassName = targetClassName + "Test";

        // 初始化统计
        iterationStats = new IterationStats(targetFile);

        // ===== 获取方法覆盖率列表（按覆盖率排序，低的在前）=====
        List<MethodCoverageInfo> methodsToProcess = currentPreCheck != null
                ? currentPreCheck.getMethodsSortedByCoverage()
                : new ArrayList<>();

        if (methodsToProcess.isEmpty()) {
            log.info("No method coverage info available, falling back to LLM-driven iteration");
            runIterativeFallback(targetFile, taskContext, systemPrompt, projectRoot);
            return;
        }

        log.info("📊 Found {} methods to process (sorted by coverage):", methodsToProcess.size());
        for (MethodCoverageInfo m : methodsToProcess) {
            log.info("   - {} [{}] Line: {}%, Branch: {}%",
                    m.getMethodName(), m.getPriority(),
                    String.format("%.1f", m.getLineCoverage()),
                    String.format("%.1f", m.getBranchCoverage()));
        }

        // ===== Phase 1: 初始化（创建测试文件骨架）=====
        log.info(">>> Phase 1: Initialization");

        // 切换到分析阶段工具集
        if (phaseManager.isIterativeMode()) {
            phaseManager.switchToPhase(WorkflowPhase.ANALYSIS, toolRegistry);
            log.info("🔧 Switched to ANALYSIS phase ({} tools)", toolRegistry.size());
        }

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

        // ===== Phase 2: 逐方法迭代（使用自动验证管道）=====
        int processedCount = 0;
        int skippedCount = 0;
        final int maxMethodRetries = 3;
        final int maxVerificationRetries = 3;

        for (int i = 0; i < methodsToProcess.size(); i++) {
            MethodCoverageInfo methodInfo = methodsToProcess.get(i);
            log.info(">>> Phase 2: Method #{} - {} [{}]", i + 1, methodInfo.getMethodName(), methodInfo.getPriority());

            // 创建方法统计
            IterationStats.MethodStats currentMethodStats = iterationStats.startMethod(
                    methodInfo.getMethodName(),
                    methodInfo.getPriority(),
                    methodInfo.getLineCoverage());

            // 检查是否已达到覆盖率要求
            if (methodInfo.getLineCoverage() >= coverageThreshold) {
                log.info("📊 Method {} already has {}% coverage (threshold: {}%) - SKIPPING",
                        methodInfo.getMethodName(), String.format("%.1f", methodInfo.getLineCoverage()),
                        coverageThreshold);
                currentMethodStats.markSkipped("Coverage already met");
                currentMethodStats.complete("SKIPPED", methodInfo.getLineCoverage());
                skippedCount++;
                continue;
            }

            processedCount++;
            boolean methodCompleted = false;
            int coverageRetryCount = 0;
            double currentCoverage = methodInfo.getLineCoverage();

            // 外层循环：覆盖率不足时继续生成测试
            while (!methodCompleted && coverageRetryCount < maxMethodRetries) {

                // Step 1: 让 LLM 生成测试代码
                // 切换到生成阶段工具集
                if (phaseManager.isIterativeMode()) {
                    phaseManager.switchToPhase(WorkflowPhase.GENERATION, toolRegistry);
                    log.debug("🔧 Switched to GENERATION phase ({} tools)", toolRegistry.size());
                }

                log.info("🤖 Step 1: Generating tests for method {}", methodInfo.getMethodName());
                String generatePrompt = coverageRetryCount == 0
                        ? FixPromptBuilder.buildGenerateTestPrompt(targetFile, methodInfo.getMethodName(),
                                testFilePath, currentCoverage)
                        : FixPromptBuilder.buildMoreTestsPrompt(targetFile, methodInfo.getMethodName(),
                                testFilePath, currentCoverage, coverageThreshold);

                boolean codeGenerated = runLlmAndWait(systemPrompt, generatePrompt, currentMethodStats);
                if (!codeGenerated) {
                    log.error("❌ Failed to generate test code for method {}", methodInfo.getMethodName());
                    currentMethodStats.complete("FAILED", currentCoverage);
                    methodCompleted = true;
                    continue;
                }

                // Step 2: 自动执行验证管道（带修复循环）
                // 切换到验证阶段工具集
                if (phaseManager.isIterativeMode()) {
                    phaseManager.switchToPhase(WorkflowPhase.VERIFICATION, toolRegistry);
                    log.debug("🔧 Switched to VERIFICATION phase ({} tools)", toolRegistry.size());
                }

                log.info("🔄 Step 2: Running verification pipeline");
                int verificationRetryCount = 0;
                VerificationResult verifyResult = null;

                while (verificationRetryCount < maxVerificationRetries) {
                    verifyResult = verificationPipeline.execute(
                            testFilePath, testClassName, targetClassName,
                            methodInfo.getMethodName(), projectRoot);

                    if (verifyResult.isSuccess()) {
                        break;
                    }

                    // 验证失败，切换到修复阶段，调用 LLM 修复
                    if (phaseManager.isIterativeMode()) {
                        phaseManager.switchToPhase(WorkflowPhase.REPAIR, toolRegistry);
                        log.debug("🔧 Switched to REPAIR phase ({} tools)", toolRegistry.size());
                    }

                    log.warn("⚠️ Verification failed at step: {}", verifyResult.getFailedStep());
                    String fixPrompt = buildFixPromptForStep(verifyResult, testFilePath, testClassName);

                    boolean fixed = runLlmAndWait(systemPrompt, fixPrompt, currentMethodStats);
                    if (!fixed) {
                        log.error("❌ Failed to fix error");
                        break;
                    }

                    verificationRetryCount++;
                    log.info("🔄 Retrying verification (attempt {}/{})",
                            verificationRetryCount + 1, maxVerificationRetries);
                }

                // 检查验证结果
                if (verifyResult == null || !verifyResult.isSuccess()) {
                    log.error("❌ Verification failed after {} attempts", maxVerificationRetries);
                    currentMethodStats.complete("FAILED", currentCoverage);
                    methodCompleted = true;
                    continue;
                }

                // 验证成功，检查覆盖率
                currentCoverage = verifyResult.getCoverage();
                currentMethodStats.incrementIteration();

                if (verifyResult.isCoverageThresholdMet()) {
                    log.info("✅ Method {} completed with coverage: {}%",
                            methodInfo.getMethodName(), String.format("%.1f", currentCoverage));
                    currentMethodStats.complete("SUCCESS", currentCoverage);
                    methodCompleted = true;
                } else {
                    log.info("⚠️ Coverage {:.1f}% below threshold {}%, generating more tests",
                            currentCoverage, coverageThreshold);
                    coverageRetryCount++;
                }
            }

            if (!methodCompleted) {
                log.warn("⚠️ Method {} completed with coverage below threshold: {}%",
                        methodInfo.getMethodName(), String.format("%.1f", currentCoverage));
                currentMethodStats.complete("PARTIAL", currentCoverage);
            }
        }

        // ===== Phase 3: 汇总 =====
        log.info(">>> Phase 3: Summary");
        log.info("📊 Processed: {}, Skipped: {}, Total: {}",
                processedCount, skippedCount, methodsToProcess.size());

        // 生成报告
        String agentDir = getAgentRunDirectory();
        generateReport(agentDir);
    }

    /**
     * 运行 LLM 并等待完成
     */
    private boolean runLlmAndWait(String systemPrompt, String userPrompt,
            IterationStats.MethodStats methodStats) {
        AgentExecutor executor = createExecutor(systemPrompt, 10);

        if (methodStats != null) {
            executor.setTokenStatsCallback((prompt, response) -> {
                methodStats.addPromptTokens(prompt);
                methodStats.addResponseTokens(response);
            });
        }

        ConsoleStreamingHandler handler = new ConsoleStreamingHandler();
        executor.runStream(userPrompt, handler);

        try {
            handler.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        if (handler.getError() != null) {
            log.error("LLM call failed: {}", handler.getError().getMessage());
            return false;
        }

        String content = handler.getContent();
        return content != null && !content.trim().isEmpty();
    }

    /**
     * 根据验证失败的步骤构建修复提示词
     */
    private String buildFixPromptForStep(VerificationResult result, String testFilePath, String testClassName) {
        VerificationStep failedStep = result.getFailedStep();
        String errorDetails = result.getErrorDetails() != null ? result.getErrorDetails() : result.getErrorMessage();

        return switch (failedStep) {
            case SYNTAX_CHECK -> FixPromptBuilder.buildSyntaxFixPrompt(testFilePath, errorDetails);
            case LSP_CHECK -> FixPromptBuilder.buildLspFixPrompt(testFilePath, errorDetails);
            case COMPILE -> FixPromptBuilder.buildCompileFixPrompt(testFilePath, errorDetails);
            case TEST -> FixPromptBuilder.buildTestFixPrompt(testFilePath, testClassName, errorDetails);
            case COVERAGE -> ""; // 覆盖率不足在外层处理
        };
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
                iteration, methodInfo.getMethodName(),
                targetFile,
                methodInfo.getMethodName(), methodInfo.getPriority(),
                methodInfo.getLineCoverage(), methodInfo.getBranchCoverage()));

        // 添加覆盖率反馈建议（如果有）
        if (currentPreCheck != null && currentPreCheck.getFeedbackResult() != null) {
            CoverageFeedbackEngine.FeedbackResult feedback = currentPreCheck.getFeedbackResult();
            List<CoverageFeedbackEngine.ImprovementSuggestion> suggestions = feedback.getImprovements();

            // 查找与当前方法相关的建议
            List<CoverageFeedbackEngine.ImprovementSuggestion> methodSuggestions = suggestions.stream()
                    .filter(s -> s.getMethodName() != null && s.getMethodName().contains(methodInfo.getMethodName()))
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
                **Your Task (STRICT ORDER - DO NOT SKIP STEPS):**

                📝 **Step 1: Read & Analyze**
                1.1 readFile(testFilePath) - Read the current test file
                1.2 Analyze method `%s` to identify uncovered paths

                📝 **Step 2: Generate & Write Tests**
                2.1 Generate tests for THIS METHOD ONLY (not other methods!)
                2.2 writeFileFromLine to APPEND tests (do NOT overwrite existing tests)

                ⚠️ **Step 3: Syntax Check (MANDATORY)**
                3.1 checkSyntax(testFilePath) or checkSyntaxWithLsp(testFilePath)
                3.2 IF LSP_ERRORS → FIX errors → checkSyntax again
                3.3 ONLY proceed when LSP_OK

                🔨 **Step 4: Compile & Test (AFTER syntax check passes!)**
                4.1 compileProject() ← MUST call after checkSyntax returns LSP_OK
                4.2 executeTest(testClassName) ← Run tests
                4.3 IF test fails → FIX → checkSyntax → compileProject → executeTest

                📊 **Step 5: Verify Coverage**
                5.1 getSingleMethodCoverage(modulePath, className, "%s")

                ✅ **Step 6: Complete**
                6.1 completeCurrentMethod(status, coverage, notes)

                ❌ **DO NOT:**
                - Skip compileProject after checkSyntax passes
                - Call getPriorityMethods or initMethodIteration (already initialized!)
                - Generate tests for OTHER methods
                - Re-read the source file unnecessarily

                After Step 6, STOP and wait for next method.
                """, methodInfo.getMethodName(), methodInfo.getMethodName()));

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
            if (result != null && !result.toLowerCase().startsWith("error")) {
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

        // 添加明确的测试文件路径指导
        String testFilePath = calculateTestFilePath(targetFile);
        message.append("\nTest file path: ").append(testFilePath);
        message.append("\n\n⚠️ CRITICAL: You MUST write the test file to the exact path above (").append(testFilePath)
                .append(").");

        // 添加预检查结果信息
        if (currentPreCheck != null) {
            if (currentPreCheck.isHasExistingTests()) {
                message.append("\n\n## Pre-check Results\n");
                message.append("✅ Project compiled successfully\n");
                message.append("✅ Existing test file found\n");

                if (currentPreCheck.getCoverageInfo() != null && !currentPreCheck.getCoverageInfo().isEmpty()) {
                    message.append("\n### Current Coverage Analysis:\n");
                    message.append("```\n");
                    message.append(currentPreCheck.getCoverageInfo());
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

            if (currentPreCheck.isHasExistingTests()) {
                sb.append("✅ Existing test file found\n");

                if (currentPreCheck.getCoverageInfo() != null && !currentPreCheck.getCoverageInfo().isEmpty()) {
                    sb.append("\n### Current Coverage Analysis:\n");
                    sb.append("```\n");
                    sb.append(currentPreCheck.getCoverageInfo());
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
                4. Create the test file skeleton if it doesn't exist (writeFile with basic imports and class declaration)

                ⚠️ IMPORTANT:
                - DO NOT call getPriorityMethods or initMethodIteration - already done by system
                - DO NOT call getNextMethod - the orchestrator will handle method iteration
                - Just ensure the test file exists with proper skeleton

                After creating the test skeleton, STOP and wait for next instruction.
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

                **Steps (STRICT ORDER - DO NOT SKIP):**

                1️⃣ getNextMethod() → Get current method info
                   - If "ITERATION_COMPLETE" → getIterationProgress() → STOP

                2️⃣ readFile(testFilePath) → Read current test file

                3️⃣ Generate & write tests for THIS METHOD ONLY
                   - Use writeFileFromLine to APPEND (not overwrite)

                4️⃣ **SYNTAX CHECK (MANDATORY)**
                   - checkSyntax(testFilePath)
                   - IF LSP_ERRORS → fix → checkSyntax again
                   - ONLY proceed when LSP_OK

                5️⃣ **COMPILE & TEST (AFTER syntax check passes!)**
                   - compileProject() ← MUST call!
                   - executeTest(testClassName)

                6️⃣ getSingleMethodCoverage(modulePath, className, methodName)

                7️⃣ completeCurrentMethod(status, coverage, notes)

                ❌ DO NOT skip compileProject after checkSyntax passes!
                ❌ DO NOT call getPriorityMethods or initMethodIteration again!

                After Step 7, STOP.
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
                String content = PromptTemplateLoader.loadTemplate(resourcePath);
                if (!content.isEmpty()) {
                    return content;
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
     * 执行预检查：编译工程和覆盖率分析
     */
    private PreCheckResult performPreCheck(String projectRoot, String targetFile) {
        return preCheckExecutor.execute(projectRoot, targetFile);
    }

    /**
     * 提取全限定类名
     */
    private String extractClassName(String sourceFile) {
        return ClassNameExtractor.extractClassName(sourceFile);
    }

    /**
     * 计算测试文件路径
     */
    private String calculateTestFilePath(String targetFile) {
        // 处理带前导斜杠和不带前导斜杠的路径
        String testPath = targetFile;
        if (testPath.contains("/src/main/java/")) {
            testPath = testPath.replace("/src/main/java/", "/src/test/java/");
        } else if (testPath.contains("src/main/java/")) {
            testPath = testPath.replace("src/main/java/", "src/test/java/");
        }
        return testPath.replace(".java", "Test.java");
    }
}
