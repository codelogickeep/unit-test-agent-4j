package com.codelogickeep.agent.ut.framework.precheck;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import com.codelogickeep.agent.ut.model.MethodCoverageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 覆盖率分析器 - 负责解析和分析覆盖率报告
 */
public class CoverageAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(CoverageAnalyzer.class);
    private static final Pattern COVERAGE_PATTERN = Pattern.compile("([✓✗])\\s+(.+?)\\s+Line:\\s+(\\d+\\.\\d+)%\\s+Branch:\\s+(\\d+\\.\\d+)%");

    private final ToolRegistry toolRegistry;
    private final AppConfig config;

    public CoverageAnalyzer(ToolRegistry toolRegistry, AppConfig config) {
        this.toolRegistry = toolRegistry;
        this.config = config;
    }

    /**
     * 分析覆盖率
     */
    public CoverageResult analyze(String projectRoot, String targetFile) {
        String coverageInfo = null;
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
            String uncoveredMethods = toolRegistry.invoke("getUncoveredMethods", uncoveredArgs);

            if (coverageInfo != null && !coverageInfo.startsWith("ERROR")) {
                System.out.println("✅ Coverage analysis complete:");
                printCoverageSummary(coverageInfo);
                methodCoverages = parseMethodCoverage(coverageInfo, threshold);

                if (uncoveredMethods != null && !uncoveredMethods.startsWith("ERROR")) {
                    coverageInfo = coverageInfo + "\n\n" + uncoveredMethods;
                }
            } else {
                System.out.println("⚠️ Could not get coverage details (no JaCoCo report found)");
                coverageInfo = attemptStaticAnalysis(targetFile, methodCoverages);
            }
        } catch (Exception e) {
            log.warn("Failed to get coverage: {}", e.getMessage());
            System.out.println("⚠️ Could not analyze coverage: " + e.getMessage());
        }

        return new CoverageResult(coverageInfo, methodCoverages);
    }

    private void printCoverageSummary(String coverageInfo) {
        String[] lines = coverageInfo.split("\n");
        for (int i = 0; i < Math.min(15, lines.length); i++) {
            System.out.println("   " + lines[i]);
        }
        if (lines.length > 15) {
            System.out.println("   ... (" + (lines.length - 15) + " more lines)");
        }
    }

    private List<MethodCoverageInfo> parseMethodCoverage(String coverageInfo, int threshold) {
        List<MethodCoverageInfo> methods = new ArrayList<>();
        if (coverageInfo == null || coverageInfo.isEmpty()) {
            return methods;
        }

        Matcher matcher = COVERAGE_PATTERN.matcher(coverageInfo);
        while (matcher.find()) {
            String methodName = matcher.group(2).trim();
            double lineCoverage = Double.parseDouble(matcher.group(3));
            double branchCoverage = Double.parseDouble(matcher.group(4));

            String priority = determinePriority(lineCoverage, branchCoverage, threshold);
            methods.add(new MethodCoverageInfo(methodName, priority, lineCoverage, branchCoverage));
        }

        return methods;
    }

    private String determinePriority(double lineCoverage, double branchCoverage, int threshold) {
        if (lineCoverage == 0.0 && branchCoverage == 0.0) {
            return "P0";
        } else if (lineCoverage < threshold || branchCoverage < threshold) {
            return "P1";
        } else {
            return "P2";
        }
    }

    private String attemptStaticAnalysis(String targetFile, List<MethodCoverageInfo> methodCoverages) {
        try {
            System.out.println("ℹ️ Attempting static analysis to discover methods...");
            Map<String, Object> analyzeArgs = new HashMap<>();
            analyzeArgs.put("path", targetFile);
            String analysisResult = toolRegistry.invoke("analyzeClass", analyzeArgs);

            if (analysisResult != null && !analysisResult.startsWith("ERROR")) {
                List<String> methodNames = extractMethodNamesFromAnalysis(analysisResult);
                if (!methodNames.isEmpty()) {
                    System.out.println("✅ Discovered " + methodNames.size() + " methods via static analysis");
                    StringBuilder sb = new StringBuilder("Static Analysis Result (No coverage data yet):\n");
                    for (String method : methodNames) {
                        methodCoverages.add(new MethodCoverageInfo(method, "P0", 0.0, 0.0));
                        sb.append(String.format("✗ %s Line: 0.0%% Branch: 0.0%%\n", method));
                    }
                    return sb.toString();
                }
            }
        } catch (Exception ex) {
            log.warn("Static analysis fallback failed", ex);
        }
        return null;
    }

    private List<String> extractMethodNamesFromAnalysis(String analysisResult) {
        List<String> methodNames = new ArrayList<>();
        Pattern methodPattern = Pattern.compile("Method:\\s+(.+?)\\s*\\(");
        Matcher matcher = methodPattern.matcher(analysisResult);
        while (matcher.find()) {
            methodNames.add(matcher.group(1).trim());
        }
        return methodNames;
    }

    private String extractClassName(String targetFile) {
        String fileName = Paths.get(targetFile).getFileName().toString();
        return fileName.replace(".java", "");
    }

    /**
     * 覆盖率分析结果
     */
    public static class CoverageResult {
        private final String coverageInfo;
        private final List<MethodCoverageInfo> methodCoverages;

        public CoverageResult(String coverageInfo, List<MethodCoverageInfo> methodCoverages) {
            this.coverageInfo = coverageInfo;
            this.methodCoverages = methodCoverages;
        }

        public String getCoverageInfo() {
            return coverageInfo;
        }

        public List<MethodCoverageInfo> getMethodCoverages() {
            return methodCoverages;
        }
    }
}
