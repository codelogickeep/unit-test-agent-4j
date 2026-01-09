package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.model.TestTask;
import com.codelogickeep.agent.ut.model.UncoveredMethod;
import com.codelogickeep.agent.ut.tools.CoverageTool;
import com.codelogickeep.agent.ut.tools.ProjectScannerTool;
import com.codelogickeep.agent.ut.tools.TestDiscoveryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch analyzer for pre-processing project before LLM invocation.
 * Reduces token consumption by analyzing coverage and identifying uncovered methods in Java.
 */
public class BatchAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(BatchAnalyzer.class);

    private final ProjectScannerTool scannerTool;
    private final TestDiscoveryTool discoveryTool;
    private final CoverageTool coverageTool;
    private final String projectRoot;
    private final int coverageThreshold;

    public BatchAnalyzer(String projectRoot, int coverageThreshold) {
        this.projectRoot = projectRoot;
        this.coverageThreshold = coverageThreshold;
        this.scannerTool = new ProjectScannerTool();
        this.discoveryTool = new TestDiscoveryTool();
        this.coverageTool = new CoverageTool();
    }

    /**
     * Analyze project and return list of test tasks.
     * Each task contains only uncovered methods that need tests.
     */
    public List<TestTask> analyze(String excludePatterns) throws IOException {
        log.info("Starting batch analysis for project: {}", projectRoot);
        List<TestTask> tasks = new ArrayList<>();

        // 1. Scan for core source classes
        List<String> sourceClasses = scannerTool.getSourceClassPaths(projectRoot, excludePatterns);
        log.info("Found {} core source classes", sourceClasses.size());

        // 2. For each source class, check coverage and find uncovered methods
        for (String sourcePath : sourceClasses) {
            try {
                TestTask task = analyzeClass(sourcePath);
                if (task != null && !task.getUncoveredMethods().isEmpty()) {
                    tasks.add(task);
                }
            } catch (Exception e) {
                log.warn("Failed to analyze class: {}", sourcePath, e);
            }
        }

        log.info("Analysis complete. {} classes need tests", tasks.size());
        return tasks;
    }

    /**
     * Analyze a single source class.
     */
    public TestTask analyzeClass(String sourcePath) throws IOException {
        // Extract class name from path
        String className = extractClassName(sourcePath);
        if (className == null) {
            return null;
        }

        // Find existing test class
        String testPath = null;
        try {
            String result = discoveryTool.findTestClasses(sourcePath, projectRoot);
            if (!result.startsWith("No existing test")) {
                // Extract first test path from result
                String[] lines = result.split("\n");
                for (String line : lines) {
                    if (line.trim().startsWith("- ")) {
                        testPath = line.trim().substring(2).trim();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("No test class found for: {}", sourcePath);
        }

        // Get uncovered methods from coverage report
        List<UncoveredMethod> uncoveredMethods = coverageTool.getUncoveredMethodsList(
                projectRoot, className, coverageThreshold);

        // If no coverage data, assume all methods need tests
        if (uncoveredMethods.isEmpty() && testPath == null) {
            // New class without tests - will be handled by LLM
            return TestTask.builder()
                    .sourceFilePath(sourcePath)
                    .testFilePath(null)
                    .className(className)
                    .currentCoverage(0)
                    .uncoveredMethods(List.of(UncoveredMethod.builder()
                            .methodName("*")
                            .signature("(all methods)")
                            .lineCoverage(0)
                            .build()))
                    .build();
        }

        if (uncoveredMethods.isEmpty()) {
            return null; // All methods covered
        }

        return TestTask.builder()
                .sourceFilePath(sourcePath)
                .testFilePath(testPath)
                .className(className)
                .currentCoverage(100 - (uncoveredMethods.size() * 10.0)) // Rough estimate
                .uncoveredMethods(uncoveredMethods)
                .build();
    }

    /**
     * Extract fully qualified class name from source path.
     */
    private String extractClassName(String sourcePath) {
        String normalized = sourcePath.replace('\\', '/');

        // Find src/main/java and extract package + class
        int srcMainIdx = normalized.indexOf("src/main/java/");
        if (srcMainIdx < 0) {
            return null;
        }

        String classPath = normalized.substring(srcMainIdx + "src/main/java/".length());
        if (!classPath.endsWith(".java")) {
            return null;
        }

        return classPath.replace(".java", "").replace('/', '.');
    }

    /**
     * Build compact task prompt for LLM (minimal tokens).
     */
    public String buildTaskPrompt(TestTask task) {
        StringBuilder sb = new StringBuilder();

        sb.append("Generate tests for: ").append(task.getClassName()).append("\n");
        sb.append("Source: ").append(task.getSourceFilePath()).append("\n");

        if (task.getTestFilePath() != null) {
            sb.append("Test file: ").append(task.getTestFilePath()).append(" (APPEND)\n");
        } else {
            sb.append("Test file: CREATE NEW\n");
        }

        sb.append("\nUncovered methods:\n");
        for (UncoveredMethod m : task.getUncoveredMethods()) {
            sb.append("- ").append(m.getSignature());
            if (m.getLineCoverage() > 0) {
                sb.append(" (").append(String.format("%.0f%%", m.getLineCoverage())).append(" covered)");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Print analysis report (for dry-run mode).
     */
    public void printReport(List<TestTask> tasks) {
        System.out.println("\n=== Batch Analysis Report ===\n");
        System.out.println("Project: " + projectRoot);
        System.out.println("Coverage Threshold: " + coverageThreshold + "%");
        System.out.println("Classes needing tests: " + tasks.size());
        System.out.println();

        int totalMethods = 0;
        for (TestTask task : tasks) {
            System.out.println("─".repeat(60));
            System.out.println("Class: " + task.getClassName());
            System.out.println("Source: " + task.getSourceFilePath());
            System.out.println("Test: " + (task.getTestFilePath() != null ? task.getTestFilePath() : "(new)"));
            System.out.println("Uncovered methods:");
            for (UncoveredMethod m : task.getUncoveredMethods()) {
                System.out.println("  - " + m.getSignature() + " : " + String.format("%.0f%%", m.getLineCoverage()));
                totalMethods++;
            }
        }

        System.out.println("─".repeat(60));
        System.out.println("\nTotal: " + tasks.size() + " classes, " + totalMethods + " methods need tests");
    }
}
