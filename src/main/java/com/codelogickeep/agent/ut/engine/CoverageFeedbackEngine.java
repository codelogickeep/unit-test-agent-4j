package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.tools.BoundaryAnalyzerTool;
import com.codelogickeep.agent.ut.tools.CoverageTool;
import com.codelogickeep.agent.ut.tools.MutationTestTool;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 覆盖率反馈引擎 - 实现智能的测试质量提升循环
 * 
 * 工作流程：
 * 1. 分析当前覆盖率状态
 * 2. 识别未覆盖/弱覆盖的代码区域
 * 3. 结合边界分析和变异测试结果
 * 4. 生成优先级排序的改进建议
 * 5. 跟踪多轮迭代进度
 */
@Slf4j
@RequiredArgsConstructor
public class CoverageFeedbackEngine {

    private final CoverageTool coverageTool;
    private final BoundaryAnalyzerTool boundaryAnalyzerTool;
    private final MutationTestTool mutationTestTool;

    private final List<FeedbackIteration> iterationHistory = new ArrayList<>();
    private int currentIteration = 0;

    // 停滞检测配置
    private int maxStaleIterations = 3;          // 最大无进展迭代次数
    private int minCoverageGainPerIteration = 1; // 每次迭代最小覆盖率提升
    private StopReason lastStopReason = null;
    
    /**
     * 设置停滞检测配置
     */
    public void configureStagnationDetection(int maxStaleIterations, int minCoverageGain) {
        this.maxStaleIterations = Math.max(1, maxStaleIterations);
        this.minCoverageGainPerIteration = Math.max(0, minCoverageGain);
        log.info("Stagnation detection configured: maxStaleIterations={}, minCoverageGain={}%", 
                this.maxStaleIterations, this.minCoverageGainPerIteration);
    }

    /**
     * 执行一轮覆盖率反馈分析
     */
    public FeedbackResult runFeedbackCycle(String projectPath, String className, int targetCoverage) throws IOException {
        log.info("Starting feedback cycle {} for class: {}, target: {}%", 
                ++currentIteration, className, targetCoverage);

        FeedbackResult.FeedbackResultBuilder resultBuilder = FeedbackResult.builder()
                .iteration(currentIteration)
                .className(className)
                .targetCoverage(targetCoverage);

        // 1. 获取当前覆盖率
        String coverageReport = coverageTool.checkCoverageThreshold(projectPath, className, targetCoverage);
        int currentCoverage = parseCoveragePercentage(coverageReport);
        resultBuilder.currentCoverage(currentCoverage);

        log.info("Current coverage: {}%, target: {}%", currentCoverage, targetCoverage);

        // 2. 检查是否达标
        if (currentCoverage >= targetCoverage) {
            log.info("Coverage target met! No further action needed.");
            return resultBuilder
                    .targetMet(true)
                    .improvements(new ArrayList<>())
                    .nextAction(NextAction.NONE)
                    .build();
        }

        // 3. 获取未覆盖的方法
        String uncoveredMethods = coverageTool.getUncoveredMethodsCompact(projectPath, className, targetCoverage);
        List<String> uncoveredMethodList = parseUncoveredMethods(uncoveredMethods);
        resultBuilder.uncoveredMethods(uncoveredMethodList);

        // 4. 分析未覆盖区域的边界条件
        List<ImprovementSuggestion> improvements = new ArrayList<>();
        
        String sourceFile = findSourceFile(projectPath, className);
        if (sourceFile != null) {
            try {
                BoundaryAnalyzerTool.BoundaryAnalysisResult boundaryResult = 
                        boundaryAnalyzerTool.analyzeClassBoundaries(sourceFile);
                
                // 将边界分析转换为改进建议
                for (String suggestion : boundaryResult.getTestSuggestions()) {
                    improvements.add(ImprovementSuggestion.builder()
                            .type(SuggestionType.BOUNDARY_TEST)
                            .description(suggestion)
                            .priority(calculatePriority(suggestion, uncoveredMethodList))
                            .build());
                }
            } catch (Exception e) {
                log.warn("Failed to analyze boundaries for {}: {}", sourceFile, e.getMessage());
            }
        }

        // 5. 分析现有测试的有效性（可选：变异测试）
        // 注意：变异测试耗时较长，可以配置是否启用
        boolean runMutationAnalysis = iterationHistory.size() > 2 && currentCoverage > 50;
        if (runMutationAnalysis) {
            try {
                MutationTestTool.MutationTestResult mutationResult = 
                        mutationTestTool.parsePitestReport(projectPath, className);
                
                if (mutationResult.getSurvivedMutations() > 0) {
                    for (MutationTestTool.MutationDetail detail : mutationResult.getSurvivedDetails()) {
                        improvements.add(ImprovementSuggestion.builder()
                                .type(SuggestionType.MUTATION_SURVIVOR)
                                .description(String.format("Strengthen test for %s at line %d (%s)",
                                        detail.getMutatedMethod(), detail.getLineNumber(), detail.getMutator()))
                                .priority(Priority.HIGH)
                                .lineNumber(detail.getLineNumber())
                                .build());
                    }
                }
            } catch (Exception e) {
                log.debug("Mutation analysis not available: {}", e.getMessage());
            }
        }

        // 6. 为未覆盖的方法添加基本测试建议
        for (String method : uncoveredMethodList) {
            improvements.add(ImprovementSuggestion.builder()
                    .type(SuggestionType.MISSING_TEST)
                    .description("Add test for uncovered method: " + method)
                    .priority(Priority.HIGH)
                    .methodName(method)
                    .build());
        }

        // 7. 按优先级排序
        improvements.sort(Comparator.comparing(ImprovementSuggestion::getPriority));
        resultBuilder.improvements(improvements);

        // 8. 确定下一步行动
        NextAction nextAction = determineNextAction(currentCoverage, targetCoverage, improvements, currentIteration);
        resultBuilder.nextAction(nextAction);

        // 9. 记录迭代历史
        FeedbackResult result = resultBuilder.targetMet(false).build();
        recordIteration(result);

        log.info("Feedback cycle {} complete: {} improvements suggested, next action: {}",
                currentIteration, improvements.size(), nextAction);

        return result;
    }

    /**
     * 获取反馈历史摘要
     */
    public String getIterationSummary() {
        if (iterationHistory.isEmpty()) {
            return "No feedback iterations recorded yet.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Coverage Feedback History:\n");
        sb.append("==========================\n\n");

        for (FeedbackIteration iteration : iterationHistory) {
            sb.append(String.format("Iteration %d (at %s):\n", 
                    iteration.getIterationNumber(), iteration.getTimestamp()));
            sb.append(String.format("  Coverage: %d%% -> Target: %d%%\n",
                    iteration.getCoverageAtStart(), iteration.getTargetCoverage()));
            sb.append(String.format("  Improvements Applied: %d\n", iteration.getImprovementsApplied()));
            sb.append(String.format("  Result: %s\n\n", iteration.getResult()));
        }

        // 计算趋势
        if (iterationHistory.size() >= 2) {
            int firstCoverage = iterationHistory.get(0).getCoverageAtStart();
            int lastCoverage = iterationHistory.get(iterationHistory.size() - 1).getCoverageAtStart();
            int improvement = lastCoverage - firstCoverage;
            
            sb.append(String.format("Overall Progress: %d%% -> %d%% (%+d%%)\n", 
                    firstCoverage, lastCoverage, improvement));
        }

        return sb.toString();
    }

    /**
     * 检查是否应该继续迭代
     */
    public boolean shouldContinueIterating(int maxIterations) {
        lastStopReason = null;
        
        // 1. 检查最大迭代次数
        if (currentIteration >= maxIterations) {
            lastStopReason = StopReason.MAX_ITERATIONS_REACHED;
            log.info("Stop reason: Reached maximum iterations ({})", maxIterations);
            return false;
        }

        // 2. 检查目标是否已达成
        if (!iterationHistory.isEmpty()) {
            FeedbackIteration lastIteration = iterationHistory.get(iterationHistory.size() - 1);
            if ("TARGET_MET".equals(lastIteration.getResult())) {
                lastStopReason = StopReason.TARGET_ACHIEVED;
                log.info("Stop reason: Coverage target achieved");
                return false;
            }
        }

        // 3. 智能停滞检测
        if (iterationHistory.size() >= maxStaleIterations) {
            List<FeedbackIteration> recentIterations = iterationHistory.subList(
                    iterationHistory.size() - maxStaleIterations, iterationHistory.size());
            
            // 检查是否完全没有进展
            int firstCoverage = recentIterations.get(0).getCoverageAtStart();
            int lastCoverage = recentIterations.get(recentIterations.size() - 1).getCoverageAtStart();
            int totalGain = lastCoverage - firstCoverage;
            int expectedGain = minCoverageGainPerIteration * maxStaleIterations;
            
            if (totalGain < expectedGain) {
                lastStopReason = StopReason.STAGNATION_DETECTED;
                log.info("Stop reason: Stagnation detected - only {}% gain in last {} iterations (expected at least {}%)",
                        totalGain, maxStaleIterations, expectedGain);
                return false;
            }
            
            // 检查覆盖率是否在原地震荡
            boolean allSameCoverage = recentIterations.stream()
                    .allMatch(i -> i.getCoverageAtStart() == firstCoverage);
            if (allSameCoverage) {
                lastStopReason = StopReason.COVERAGE_PLATEAU;
                log.info("Stop reason: Coverage plateau at {}% for {} iterations",
                        firstCoverage, maxStaleIterations);
                return false;
            }
        }

        // 4. 检查是否需要人工审查（连续返回 MANUAL_REVIEW）
        if (iterationHistory.size() >= 2) {
            long manualReviewCount = iterationHistory.stream()
                    .skip(Math.max(0, iterationHistory.size() - 3))
                    .filter(i -> "MANUAL_REVIEW".equals(i.getResult()))
                    .count();
            
            if (manualReviewCount >= 2) {
                lastStopReason = StopReason.MANUAL_REVIEW_NEEDED;
                log.info("Stop reason: Multiple iterations suggest manual review needed");
                return false;
            }
        }

        return true;
    }
    
    /**
     * 检查是否应该继续迭代（带详细结果）
     */
    public ContinuationResult shouldContinueIteratingWithDetails(int maxIterations) {
        boolean shouldContinue = shouldContinueIterating(maxIterations);
        
        return ContinuationResult.builder()
                .shouldContinue(shouldContinue)
                .stopReason(lastStopReason)
                .currentIteration(currentIteration)
                .maxIterations(maxIterations)
                .recentProgress(calculateRecentProgress())
                .recommendation(generateRecommendation())
                .build();
    }
    
    /**
     * 计算最近的进展
     */
    private int calculateRecentProgress() {
        if (iterationHistory.size() < 2) {
            return 0;
        }
        int first = iterationHistory.get(0).getCoverageAtStart();
        int last = iterationHistory.get(iterationHistory.size() - 1).getCoverageAtStart();
        return last - first;
    }
    
    /**
     * 根据停止原因生成建议
     */
    private String generateRecommendation() {
        if (lastStopReason == null) {
            return "Continue iterating to improve coverage.";
        }
        
        switch (lastStopReason) {
            case TARGET_ACHIEVED:
                return "Coverage target met. Consider running mutation tests to verify test quality.";
            case MAX_ITERATIONS_REACHED:
                return "Maximum iterations reached. Review test strategy or increase iteration limit.";
            case STAGNATION_DETECTED:
                return "Coverage stagnation detected. Consider: 1) Refactoring complex code, 2) Adding integration tests, 3) Manual review of hard-to-test code.";
            case COVERAGE_PLATEAU:
                return "Coverage plateau reached. Remaining uncovered code may require: 1) Mock injection, 2) Environment-specific tests, 3) Code restructuring.";
            case MANUAL_REVIEW_NEEDED:
                return "Automatic test generation has limited further potential. Manual review recommended.";
            default:
                return "Unknown state. Please review test generation logs.";
        }
    }
    
    /**
     * 获取上次停止原因
     */
    public StopReason getLastStopReason() {
        return lastStopReason;
    }

    /**
     * 重置反馈引擎状态
     */
    public void reset() {
        iterationHistory.clear();
        currentIteration = 0;
        log.info("Feedback engine reset");
    }

    // ==================== 私有方法 ====================

    private int parseCoveragePercentage(String coverageReport) {
        // 解析覆盖率报告中的百分比
        // 示例格式: "Line Coverage: 75.5%" 或 "Coverage: 75%"
        try {
            if (coverageReport.contains("Coverage:")) {
                String[] parts = coverageReport.split("Coverage:");
                if (parts.length > 1) {
                    String percentPart = parts[1].trim().split("[%\\s]")[0];
                    return (int) Double.parseDouble(percentPart);
                }
            }
            // 默认返回 0
            return 0;
        } catch (Exception e) {
            log.warn("Failed to parse coverage percentage: {}", e.getMessage());
            return 0;
        }
    }

    private List<String> parseUncoveredMethods(String uncoveredMethodsOutput) {
        List<String> methods = new ArrayList<>();
        if (uncoveredMethodsOutput == null || uncoveredMethodsOutput.isEmpty()) {
            return methods;
        }

        for (String line : uncoveredMethodsOutput.split("\n")) {
            line = line.trim();
            if (line.startsWith("-") || line.startsWith("*")) {
                methods.add(line.substring(1).trim());
            } else if (line.contains("(") && !line.startsWith("Uncovered")) {
                // 方法签名格式
                methods.add(line.trim());
            }
        }
        return methods;
    }

    private String findSourceFile(String projectPath, String className) {
        // 从类名推导源文件路径
        String relativePath = className.replace(".", "/") + ".java";
        String[] possiblePaths = {
                projectPath + "/src/main/java/" + relativePath,
                projectPath + "\\src\\main\\java\\" + relativePath.replace("/", "\\")
        };

        for (String path : possiblePaths) {
            java.io.File file = new java.io.File(path);
            if (file.exists()) {
                return path;
            }
        }
        return null;
    }

    private Priority calculatePriority(String suggestion, List<String> uncoveredMethods) {
        // 如果建议涉及未覆盖的方法，提高优先级
        for (String method : uncoveredMethods) {
            if (suggestion.toLowerCase().contains(method.toLowerCase())) {
                return Priority.HIGH;
            }
        }
        
        // 边界测试建议默认中等优先级
        if (suggestion.contains("boundary") || suggestion.contains("null")) {
            return Priority.MEDIUM;
        }
        
        return Priority.LOW;
    }

    private NextAction determineNextAction(int currentCoverage, int targetCoverage, 
                                           List<ImprovementSuggestion> improvements, int iteration) {
        int gap = targetCoverage - currentCoverage;

        if (gap <= 0) {
            return NextAction.NONE;
        }

        if (improvements.isEmpty()) {
            return NextAction.MANUAL_REVIEW;
        }

        // 优先处理缺失测试
        long missingTests = improvements.stream()
                .filter(i -> i.getType() == SuggestionType.MISSING_TEST)
                .count();

        if (missingTests > 0) {
            return NextAction.ADD_NEW_TESTS;
        }

        // 处理弱测试
        long weakTests = improvements.stream()
                .filter(i -> i.getType() == SuggestionType.MUTATION_SURVIVOR)
                .count();

        if (weakTests > 0) {
            return NextAction.STRENGTHEN_EXISTING_TESTS;
        }

        // 添加边界测试
        return NextAction.ADD_BOUNDARY_TESTS;
    }

    private void recordIteration(FeedbackResult result) {
        FeedbackIteration iteration = FeedbackIteration.builder()
                .iterationNumber(currentIteration)
                .timestamp(java.time.LocalDateTime.now().toString())
                .coverageAtStart(result.getCurrentCoverage())
                .targetCoverage(result.getTargetCoverage())
                .improvementsApplied(result.getImprovements().size())
                .result(result.isTargetMet() ? "TARGET_MET" : "IN_PROGRESS")
                .build();
        
        iterationHistory.add(iteration);
    }

    // ==================== 枚举和数据类 ====================

    public enum SuggestionType {
        MISSING_TEST,           // 缺少测试
        BOUNDARY_TEST,          // 边界值测试
        MUTATION_SURVIVOR,      // 变异存活
        WEAK_ASSERTION,         // 弱断言
        EXCEPTION_HANDLING      // 异常处理测试
    }

    public enum Priority {
        HIGH,
        MEDIUM,
        LOW
    }

    public enum NextAction {
        NONE,                       // 目标已达成
        ADD_NEW_TESTS,              // 添加新测试
        STRENGTHEN_EXISTING_TESTS,  // 加强现有测试
        ADD_BOUNDARY_TESTS,         // 添加边界测试
        MANUAL_REVIEW               // 需要人工审查
    }
    
    /**
     * 迭代停止原因
     */
    public enum StopReason {
        TARGET_ACHIEVED("Coverage target achieved"),
        MAX_ITERATIONS_REACHED("Maximum iterations reached"),
        STAGNATION_DETECTED("Coverage progress stagnated"),
        COVERAGE_PLATEAU("Coverage plateau - no change in recent iterations"),
        MANUAL_REVIEW_NEEDED("Multiple iterations suggest manual review");
        
        private final String description;
        
        StopReason(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 继续迭代决策结果
     */
    @Data
    @Builder
    public static class ContinuationResult {
        private boolean shouldContinue;
        private StopReason stopReason;
        private int currentIteration;
        private int maxIterations;
        private int recentProgress;
        private String recommendation;
        
        public String toSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Iteration Decision ===\n");
            sb.append(String.format("Should Continue: %s\n", shouldContinue ? "YES" : "NO"));
            sb.append(String.format("Current Iteration: %d / %d\n", currentIteration, maxIterations));
            sb.append(String.format("Total Progress: +%d%%\n", recentProgress));
            
            if (stopReason != null) {
                sb.append(String.format("Stop Reason: %s\n", stopReason.getDescription()));
            }
            
            sb.append(String.format("Recommendation: %s\n", recommendation));
            sb.append("==========================\n");
            return sb.toString();
        }
    }

    @Data
    @Builder
    public static class ImprovementSuggestion {
        private SuggestionType type;
        private String description;
        private Priority priority;
        private String methodName;
        private Integer lineNumber;
    }

    @Data
    @Builder
    public static class FeedbackResult {
        private int iteration;
        private String className;
        private int currentCoverage;
        private int targetCoverage;
        private boolean targetMet;
        private List<String> uncoveredMethods;
        private List<ImprovementSuggestion> improvements;
        private NextAction nextAction;

        public String toAgentMessage() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Coverage Feedback (Iteration %d) ===\n\n", iteration));
            sb.append(String.format("Class: %s\n", className));
            sb.append(String.format("Coverage: %d%% / Target: %d%% [%s]\n\n",
                    currentCoverage, targetCoverage, targetMet ? "✓ MET" : "✗ NOT MET"));

            if (targetMet) {
                sb.append("🎉 Coverage target achieved! No further action needed.\n");
                return sb.toString();
            }

            if (uncoveredMethods != null && !uncoveredMethods.isEmpty()) {
                sb.append("Uncovered Methods:\n");
                for (String method : uncoveredMethods.stream().limit(10).collect(Collectors.toList())) {
                    sb.append("  - ").append(method).append("\n");
                }
                if (uncoveredMethods.size() > 10) {
                    sb.append(String.format("  ... and %d more\n", uncoveredMethods.size() - 10));
                }
                sb.append("\n");
            }

            if (!improvements.isEmpty()) {
                sb.append("Improvement Suggestions (by priority):\n");
                int count = 0;
                for (ImprovementSuggestion suggestion : improvements) {
                    if (count++ >= 10) {
                        sb.append(String.format("  ... and %d more suggestions\n", improvements.size() - 10));
                        break;
                    }
                    sb.append(String.format("  [%s] %s: %s\n",
                            suggestion.getPriority(), suggestion.getType(), suggestion.getDescription()));
                }
                sb.append("\n");
            }

            sb.append(String.format("Recommended Next Action: %s\n", formatNextAction(nextAction)));

            return sb.toString();
        }

        private String formatNextAction(NextAction action) {
            switch (action) {
                case ADD_NEW_TESTS:
                    return "Add new test methods for uncovered code";
                case STRENGTHEN_EXISTING_TESTS:
                    return "Strengthen existing tests with better assertions";
                case ADD_BOUNDARY_TESTS:
                    return "Add boundary value and edge case tests";
                case MANUAL_REVIEW:
                    return "Manual review needed - consider refactoring or code complexity reduction";
                default:
                    return "No action needed";
            }
        }
    }

    @Data
    @Builder
    public static class FeedbackIteration {
        private int iterationNumber;
        private String timestamp;
        private int coverageAtStart;
        private int targetCoverage;
        private int improvementsApplied;
        private String result;
    }
}
