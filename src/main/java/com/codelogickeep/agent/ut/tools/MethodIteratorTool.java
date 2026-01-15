package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.framework.annotation.P;
import com.codelogickeep.agent.ut.framework.annotation.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Tool for iterating through methods one by one for test generation.
 * Manages iteration state and tracks progress.
 */
public class MethodIteratorTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(MethodIteratorTool.class);

    private final CodeAnalyzerTool codeAnalyzerTool;
    private final CoverageTool coverageTool;

    // Project root for path resolution
    private String projectRoot;

    // Iteration state
    private String targetSourcePath;
    private String targetModulePath;
    private String targetClassName;
    private int coverageThreshold;
    private List<MethodEntry> methodQueue;
    private int currentIndex;
    private boolean initialized;
    private long startTime;

    public MethodIteratorTool(CodeAnalyzerTool codeAnalyzerTool, CoverageTool coverageTool) {
        this.codeAnalyzerTool = codeAnalyzerTool;
        this.coverageTool = coverageTool;
        this.methodQueue = new ArrayList<>();
        this.currentIndex = -1;
        this.initialized = false;
    }

    /**
     * Set project root for path resolution
     */
    public void setProjectRoot(String projectRoot) {
        this.projectRoot = projectRoot;
        log.info("MethodIteratorTool: projectRoot set to {}", projectRoot);
    }

    /**
     * Method entry for tracking
     */
    public static class MethodEntry {
        private final String name;
        private final String signature;
        private final String priority;
        private final int complexity;
        private String status;  // PENDING, IN_PROGRESS, PASS, FAIL, SKIP
        private double coverageAchieved;
        private String notes;

        public MethodEntry(String name, String signature, String priority, int complexity) {
            this.name = name;
            this.signature = signature;
            this.priority = priority;
            this.complexity = complexity;
            this.status = "PENDING";
            this.coverageAchieved = 0.0;
            this.notes = "";
        }

        public String getName() { return name; }
        public String getSignature() { return signature; }
        public String getPriority() { return priority; }
        public int getComplexity() { return complexity; }
        public String getStatus() { return status; }
        public double getCoverageAchieved() { return coverageAchieved; }
        public String getNotes() { return notes; }

        public void setStatus(String status) { this.status = status; }
        public void setCoverageAchieved(double coverage) { this.coverageAchieved = coverage; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    @Tool("Initialize method iteration for a target class. Analyzes and prioritizes methods for sequential testing.")
    public String initMethodIteration(
            @P("Path to the source file") String sourcePath,
            @P("Path to the module directory (for coverage checking)") String modulePath,
            @P("Fully qualified class name") String className,
            @P("Coverage threshold (default 80)") int threshold) throws IOException {

        log.info("Tool Input - initMethodIteration: sourcePath={}, className={}, threshold={}",
                sourcePath, className, threshold);

        this.targetSourcePath = sourcePath;
        this.targetModulePath = modulePath;
        this.targetClassName = className;
        this.coverageThreshold = threshold > 0 ? threshold : 80;
        this.methodQueue = new ArrayList<>();
        this.currentIndex = -1;
        this.startTime = System.currentTimeMillis();

        try {
            // Resolve full path using projectRoot
            String fullPath = sourcePath;
            if (projectRoot != null && !Paths.get(sourcePath).isAbsolute()) {
                fullPath = Paths.get(projectRoot, sourcePath).toString();
            }
            log.info("Resolved source path: {}", fullPath);
            
            // Get prioritized method list
            List<CodeAnalyzerTool.MethodInfo> methods = codeAnalyzerTool.getPriorityMethodsList(fullPath);

            for (CodeAnalyzerTool.MethodInfo m : methods) {
                methodQueue.add(new MethodEntry(
                        m.getName(),
                        m.getSignature(),
                        m.getPriority().name(),
                        m.getComplexity()
                ));
            }

            this.initialized = true;

            StringBuilder result = new StringBuilder();
            result.append("Method Iteration Initialized\n");
            result.append("═".repeat(50)).append("\n");
            result.append("Source: ").append(sourcePath).append("\n");
            result.append("Class: ").append(className).append("\n");
            result.append("Threshold: ").append(coverageThreshold).append("%\n");
            result.append("─".repeat(50)).append("\n");
            result.append("Methods to test (in order):\n\n");

            int idx = 1;
            for (MethodEntry entry : methodQueue) {
                result.append(String.format("  %d. [%s] %s (complexity: %d)\n",
                        idx++, entry.getPriority(), entry.getSignature(), entry.getComplexity()));
            }

            result.append("\n─".repeat(50)).append("\n");
            result.append("Total: ").append(methodQueue.size()).append(" methods\n");
            result.append("Call getNextMethod() to start testing.\n");

            String finalResult = result.toString();
            log.info("Tool Output - initMethodIteration: {} methods queued", methodQueue.size());
            return finalResult;

        } catch (Exception e) {
            log.error("Failed to initialize method iteration", e);
            return "ERROR: Failed to initialize: " + e.getMessage();
        }
    }

    @Tool("Get the next method to test. Returns method details or indicates completion. If a method is already IN_PROGRESS, returns that method instead of advancing.")
    public String getNextMethod() {
        log.info("Tool Input - getNextMethod");

        if (!initialized) {
            return "ERROR: Not initialized. Call initMethodIteration first.";
        }

        // 如果当前方法仍是 IN_PROGRESS 状态，返回它而不是前进
        // 这处理了 API 错误重试的情况
        if (currentIndex >= 0 && currentIndex < methodQueue.size()) {
            MethodEntry current = methodQueue.get(currentIndex);
            if ("IN_PROGRESS".equals(current.getStatus())) {
                log.info("Current method still IN_PROGRESS, returning same method");
                return buildMethodResponse(current);
            }
        }

        currentIndex++;

        if (currentIndex >= methodQueue.size()) {
            String result = "ITERATION_COMPLETE: All methods have been processed.\n" +
                    "Call getIterationProgress() for summary.";
            log.info("Tool Output - getNextMethod: iteration complete");
            return result;
        }

        MethodEntry current = methodQueue.get(currentIndex);
        current.setStatus("IN_PROGRESS");

        return buildMethodResponse(current);
    }

    /**
     * 构建方法响应信息
     */
    private String buildMethodResponse(MethodEntry current) {
        StringBuilder result = new StringBuilder();
        result.append("═".repeat(50)).append("\n");
        result.append("⚠️ NEW METHOD - FRESH START ⚠️\n");
        result.append("═".repeat(50)).append("\n");
        result.append("Progress: ").append(currentIndex + 1).append("/").append(methodQueue.size()).append("\n");
        result.append("─".repeat(50)).append("\n");
        result.append("FOCUS ON: ").append(current.getSignature()).append("\n");
        result.append("Priority: ").append(current.getPriority()).append("\n");
        result.append("Complexity: ").append(current.getComplexity()).append("\n");
        result.append("─".repeat(50)).append("\n");
        result.append("⚠️ IGNORE previous method's test code. This is a NEW task.\n\n");
        result.append("Steps for THIS method ONLY:\n");
        result.append("  1. Read current test file: readFile(testFilePath)\n");
        result.append("  2. Generate test ONLY for: ").append(current.getName()).append("\n");
        result.append("  3. Append tests using writeFileFromLine\n");
        result.append("  4. checkSyntax → compileProject → executeTest\n");
        result.append("  5. getSingleMethodCoverage('").append(targetModulePath)
              .append("', '").append(targetClassName).append("', '").append(current.getName()).append("')\n");
        result.append("  6. completeCurrentMethod(status, coverage, notes)\n");

        String finalResult = result.toString();
        log.info("Tool Output - getNextMethod: {} (#{}/{})",
                current.getName(), currentIndex + 1, methodQueue.size());
        return finalResult;
    }

    @Tool("Mark the current method as completed and record its status")
    public String completeCurrentMethod(
            @P("Status: PASS, FAIL, or SKIP") String status,
            @P("Coverage achieved (0-100)") double coverage,
            @P("Optional notes about the result") String notes) {

        log.info("Tool Input - completeCurrentMethod: status={}, coverage={}, notes={}",
                status, coverage, notes);

        if (!initialized) {
            return "ERROR: Not initialized.";
        }

        if (currentIndex < 0 || currentIndex >= methodQueue.size()) {
            return "ERROR: No current method. Call getNextMethod first.";
        }

        MethodEntry current = methodQueue.get(currentIndex);
        current.setStatus(status.toUpperCase());
        current.setCoverageAchieved(coverage);
        current.setNotes(notes != null ? notes : "");

        String statusIcon = switch (status.toUpperCase()) {
            case "PASS" -> "✓";
            case "FAIL" -> "✗";
            case "SKIP" -> "⊘";
            default -> "?";
        };

        StringBuilder result = new StringBuilder();
        result.append("Method Completed: ").append(current.getSignature()).append("\n");
        result.append("Status: ").append(statusIcon).append(" ").append(status.toUpperCase()).append("\n");
        result.append("Coverage: ").append(String.format("%.1f%%", coverage)).append("\n");
        if (notes != null && !notes.isEmpty()) {
            result.append("Notes: ").append(notes).append("\n");
        }
        result.append("\n");

        // Show remaining
        int remaining = methodQueue.size() - currentIndex - 1;
        if (remaining > 0) {
            result.append("Remaining: ").append(remaining).append(" methods\n");
            result.append("Call getNextMethod() to continue.\n");
        } else {
            result.append("This was the last method!\n");
            result.append("Call getIterationProgress() for final summary.\n");
        }

        String finalResult = result.toString();
        log.info("Tool Output - completeCurrentMethod: {} marked as {}",
                current.getName(), status.toUpperCase());
        return finalResult;
    }

    @Tool("Get iteration progress summary showing status of all methods")
    public String getIterationProgress() {
        log.info("Tool Input - getIterationProgress");

        if (!initialized) {
            return "ERROR: Not initialized.";
        }

        long elapsed = System.currentTimeMillis() - startTime;
        int passed = 0, failed = 0, skipped = 0, pending = 0, inProgress = 0;
        double totalCoverage = 0;
        int coverageCount = 0;

        for (MethodEntry entry : methodQueue) {
            switch (entry.getStatus()) {
                case "PASS" -> { passed++; totalCoverage += entry.getCoverageAchieved(); coverageCount++; }
                case "FAIL" -> { failed++; }
                case "SKIP" -> { skipped++; }
                case "IN_PROGRESS" -> { inProgress++; }
                default -> { pending++; }
            }
        }

        double avgCoverage = coverageCount > 0 ? totalCoverage / coverageCount : 0;

        StringBuilder result = new StringBuilder();
        result.append("Iteration Progress Summary\n");
        result.append("═".repeat(60)).append("\n");
        result.append("Class: ").append(targetClassName).append("\n");
        result.append("Elapsed: ").append(formatDuration(elapsed)).append("\n");
        result.append("─".repeat(60)).append("\n");

        result.append(String.format("Total: %d methods | ", methodQueue.size()));
        result.append(String.format("✓Pass: %d | ✗Fail: %d | ⊘Skip: %d | ○Pending: %d\n",
                passed, failed, skipped, pending + inProgress));

        if (coverageCount > 0) {
            result.append(String.format("Average Coverage (passed methods): %.1f%%\n", avgCoverage));
        }

        result.append("─".repeat(60)).append("\n");
        result.append("Method Details:\n");

        int idx = 1;
        for (MethodEntry entry : methodQueue) {
            String statusIcon = switch (entry.getStatus()) {
                case "PASS" -> "✓";
                case "FAIL" -> "✗";
                case "SKIP" -> "⊘";
                case "IN_PROGRESS" -> "►";
                default -> "○";
            };

            result.append(String.format("  %d. %s %s", idx++, statusIcon, entry.getSignature()));
            if (entry.getCoverageAchieved() > 0) {
                result.append(String.format(" [%.0f%%]", entry.getCoverageAchieved()));
            }
            if (entry.getNotes() != null && !entry.getNotes().isEmpty()) {
                result.append(" - ").append(entry.getNotes());
            }
            result.append("\n");
        }

        result.append("─".repeat(60)).append("\n");

        // Final verdict
        if (pending + inProgress == 0) {
            if (failed == 0) {
                result.append("🎉 ITERATION COMPLETE: All methods tested successfully!\n");
            } else {
                result.append("⚠ ITERATION COMPLETE: ").append(failed).append(" method(s) need attention.\n");
            }
        } else {
            result.append("Iteration in progress. Call getNextMethod() to continue.\n");
        }

        String finalResult = result.toString();
        log.info("Tool Output - getIterationProgress: {}/{} completed",
                passed + failed + skipped, methodQueue.size());
        return finalResult;
    }

    @Tool("Skip remaining low-priority methods (P2) if coverage threshold is already met")
    public String skipLowPriorityMethods() {
        log.info("Tool Input - skipLowPriorityMethods");

        if (!initialized) {
            return "ERROR: Not initialized.";
        }

        int skipped = 0;
        for (int i = currentIndex + 1; i < methodQueue.size(); i++) {
            MethodEntry entry = methodQueue.get(i);
            if ("P2_LOW".equals(entry.getPriority()) && "PENDING".equals(entry.getStatus())) {
                entry.setStatus("SKIP");
                entry.setNotes("Low priority, skipped");
                skipped++;
            }
        }

        String result = String.format("Skipped %d low-priority (P2) methods.\n" +
                "Call getNextMethod() to continue with remaining methods.", skipped);
        log.info("Tool Output - skipLowPriorityMethods: {} methods skipped", skipped);
        return result;
    }

    /**
     * Check if iteration is complete
     */
    public boolean isComplete() {
        if (!initialized) return false;
        for (MethodEntry entry : methodQueue) {
            if ("PENDING".equals(entry.getStatus()) || "IN_PROGRESS".equals(entry.getStatus())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get current method name (for programmatic use)
     */
    public String getCurrentMethodName() {
        if (currentIndex >= 0 && currentIndex < methodQueue.size()) {
            return methodQueue.get(currentIndex).getName();
        }
        return null;
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        }
        return String.format("%ds", seconds);
    }
}
