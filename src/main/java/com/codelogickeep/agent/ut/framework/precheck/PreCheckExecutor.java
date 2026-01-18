package com.codelogickeep.agent.ut.framework.precheck;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.engine.CoverageFeedbackEngine;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import com.codelogickeep.agent.ut.model.PreCheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 预检查执行器 - 负责项目环境验证、编译、测试和覆盖率分析
 */
public class PreCheckExecutor {
    private static final Logger log = LoggerFactory.getLogger(PreCheckExecutor.class);

    private final ToolRegistry toolRegistry;
    private final AppConfig config;
    private final CoverageAnalyzer coverageAnalyzer;
    private final CoverageFeedbackEngine feedbackEngine;

    public PreCheckExecutor(ToolRegistry toolRegistry, AppConfig config, CoverageFeedbackEngine feedbackEngine) {
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.feedbackEngine = feedbackEngine;
        this.coverageAnalyzer = new CoverageAnalyzer(toolRegistry, config);
    }

    /**
     * 执行预检查
     */
    public PreCheckResult execute(String projectRoot, String targetFile) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🔍 Pre-check Phase: Validating project environment");
        System.out.println("=".repeat(60));

        if (projectRoot == null) {
            return PreCheckResult.failure("Cannot determine project root from target file: " + targetFile);
        }

        // Step 1: 检查测试文件是否存在
        System.out.println("\n📄 Step 1: Checking for existing test file...");
        String testFilePath = calculateTestFilePath(targetFile);
        // 相对于项目根目录解析路径
        java.nio.file.Path testFileAbsPath = Paths.get(projectRoot).resolve(testFilePath);
        boolean hasExistingTests = Files.exists(testFileAbsPath);
        boolean skipTestExecution = false;

        if (hasExistingTests) {
            System.out.println("✅ Found existing test file: " + testFilePath);
        } else {
            System.out.println("ℹ️ No existing test file found. Will compile and create new tests.");
            if (!compileProject()) {
                return PreCheckResult.failure("Compilation failed");
            }
            skipTestExecution = true;
        }

        // Step 2: 执行测试
        if (!skipTestExecution) {
            System.out.println("\n🧪 Step 2: Running 'clean test' to generate fresh coverage data...");
            runTests();
        } else {
            System.out.println("\n🧪 Step 2: Skipping test execution (no existing tests)");
        }

        // Step 3: 分析覆盖率
        System.out.println("\n📊 Step 3: Analyzing coverage...");
        CoverageAnalyzer.CoverageResult coverageResult = coverageAnalyzer.analyze(projectRoot, targetFile);

        // Step 4: 运行覆盖率反馈分析
        CoverageFeedbackEngine.FeedbackResult feedbackResult = null;
        if (feedbackEngine != null && coverageResult.getCoverageInfo() != null) {
            System.out.println("\n🔬 Step 4: Running coverage feedback analysis...");
            feedbackResult = runFeedbackAnalysis(projectRoot, targetFile);
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("✅ Pre-check completed. Starting test generation...");
        System.out.println("=".repeat(60) + "\n");

        PreCheckResult result = PreCheckResult.success(
            coverageResult.getCoverageInfo(),
            hasExistingTests,
            coverageResult.getMethodCoverages()
        );
        result.setFeedbackResult(feedbackResult);
        return result;
    }

    private boolean compileProject() {
        try {
            String compileResult = toolRegistry.invoke("compileProject", new HashMap<>());
            if (compileResult.contains("ERROR") || compileResult.contains("exitCode=1")) {
                System.err.println("❌ Compilation failed!");
                return false;
            }
            System.out.println("✅ Compilation successful");
            return true;
        } catch (Exception e) {
            log.error("Failed to compile project", e);
            return false;
        }
    }

    private void runTests() {
        try {
            String testResult = toolRegistry.invoke("cleanAndTest", new HashMap<>());
            if (testResult.contains("exitCode=0") || testResult.contains("\"exitCode\":0")) {
                System.out.println("✅ Clean and test completed successfully");
            } else {
                System.out.println("⚠️ Some tests may have failed, continuing with coverage analysis...");
            }
        } catch (Exception e) {
            log.warn("Failed to execute tests: {}", e.getMessage());
            System.out.println("⚠️ Could not run tests: " + e.getMessage());
        }
    }

    private CoverageFeedbackEngine.FeedbackResult runFeedbackAnalysis(String projectRoot, String targetFile) {
        try {
            String className = extractClassName(targetFile);
            int threshold = config.getWorkflow() != null ? config.getWorkflow().getCoverageThreshold() : 80;
            CoverageFeedbackEngine.FeedbackResult result = feedbackEngine.runFeedbackCycle(projectRoot, className, threshold);

            if (result != null) {
                System.out.println("✅ Feedback analysis complete:");
                System.out.println("   Current coverage: " + result.getCurrentCoverage() + "%");
                System.out.println("   Target: " + result.getTargetCoverage() + "%");
                System.out.println("   Status: " + (result.isTargetMet() ? "✓ TARGET MET" : "✗ NOT MET"));
            }
            return result;
        } catch (Exception e) {
            log.warn("Feedback analysis failed: {}", e.getMessage());
            System.out.println("⚠️ Feedback analysis failed: " + e.getMessage());
            return null;
        }
    }

    private String calculateTestFilePath(String targetFile) {
        // 处理带前导斜杠和不带前导斜杠的路径
        String testPath = targetFile.replace("/src/main/java/", "/src/test/java/")
                .replace("src/main/java/", "src/test/java/");
        return testPath.replace(".java", "Test.java");
    }

    private String extractClassName(String targetFile) {
        String fileName = Paths.get(targetFile).getFileName().toString();
        return fileName.replace(".java", "");
    }
}
