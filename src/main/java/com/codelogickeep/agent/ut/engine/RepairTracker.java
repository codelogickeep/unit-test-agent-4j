package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.tools.TestReportTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Tracks repair attempts to prevent infinite loops and provide context to the Agent.
 * Records what has been tried before so the Agent can make informed decisions.
 */
public class RepairTracker {
    private static final Logger log = LoggerFactory.getLogger(RepairTracker.class);
    private static final int MAX_ATTEMPTS_PER_TEST = 5;
    private static final int MAX_TOTAL_ATTEMPTS = 20;

    private final List<RepairAttempt> attempts = new ArrayList<>();
    private final Map<String, Integer> attemptCountByTest = new HashMap<>();
    private final Set<String> unreparableTests = new HashSet<>();

    /**
     * Represents a single repair attempt.
     */
    public record RepairAttempt(
            String testName,
            TestReportTool.FailureType failureType,
            String repairAction,
            RepairResult result,
            LocalDateTime timestamp,
            String details
    ) {
        public String toSummary() {
            return String.format("[%s] %s: %s -> %s",
                    timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    testName,
                    repairAction,
                    result.name());
        }
    }

    /**
     * Result of a repair attempt.
     */
    public enum RepairResult {
        SUCCESS("Test now passes"),
        PARTIAL("Some issues fixed, others remain"),
        FAILED("Repair did not resolve the issue"),
        NEW_ERROR("Repair introduced new errors"),
        SKIPPED("Repair skipped (already tried or max attempts reached)");

        private final String description;

        RepairResult(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Records a repair attempt.
     *
     * @param testName    Name of the test being repaired
     * @param failureType Type of failure being addressed
     * @param repairAction Description of the repair action taken
     * @param result      Result of the attempt
     * @param details     Additional details about the attempt
     */
    public void recordAttempt(String testName, TestReportTool.FailureType failureType,
                              String repairAction, RepairResult result, String details) {
        RepairAttempt attempt = new RepairAttempt(
                testName, failureType, repairAction, result,
                LocalDateTime.now(), details);
        
        attempts.add(attempt);
        attemptCountByTest.merge(testName, 1, Integer::sum);

        log.info("Repair attempt recorded: {}", attempt.toSummary());

        // Mark as unreparable if max attempts reached
        if (attemptCountByTest.get(testName) >= MAX_ATTEMPTS_PER_TEST) {
            unreparableTests.add(testName);
            log.warn("Test {} marked as unreparable after {} attempts", testName, MAX_ATTEMPTS_PER_TEST);
        }
    }

    /**
     * Checks if we should continue attempting repairs.
     *
     * @return true if we should continue, false if we should stop
     */
    public boolean shouldContinue() {
        if (attempts.size() >= MAX_TOTAL_ATTEMPTS) {
            log.warn("Max total repair attempts ({}) reached", MAX_TOTAL_ATTEMPTS);
            return false;
        }
        return true;
    }

    /**
     * Checks if a specific test should be repaired.
     *
     * @param testName Name of the test
     * @return true if the test can be repaired, false if it should be skipped
     */
    public boolean canRepair(String testName) {
        if (unreparableTests.contains(testName)) {
            log.debug("Test {} is marked as unreparable", testName);
            return false;
        }
        int count = attemptCountByTest.getOrDefault(testName, 0);
        return count < MAX_ATTEMPTS_PER_TEST;
    }

    /**
     * Gets the number of attempts made for a specific test.
     *
     * @param testName Name of the test
     * @return Number of attempts
     */
    public int getAttemptCount(String testName) {
        return attemptCountByTest.getOrDefault(testName, 0);
    }

    /**
     * Gets previous repair attempts for a specific test.
     *
     * @param testName Name of the test
     * @return List of previous attempts
     */
    public List<RepairAttempt> getAttemptsForTest(String testName) {
        return attempts.stream()
                .filter(a -> a.testName.equals(testName))
                .toList();
    }

    /**
     * Gets a context summary for the Agent describing what has been tried.
     *
     * @param testName Optional test name to filter by
     * @return Summary string for the Agent
     */
    public String getRepairContext(String testName) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Repair History ===\n\n");

        List<RepairAttempt> relevantAttempts = testName != null
                ? getAttemptsForTest(testName)
                : attempts;

        if (relevantAttempts.isEmpty()) {
            sb.append("No previous repair attempts.\n");
        } else {
            sb.append("Previous attempts (").append(relevantAttempts.size()).append("):\n");
            for (RepairAttempt attempt : relevantAttempts) {
                sb.append("  • ").append(attempt.toSummary()).append("\n");
                if (attempt.details != null && !attempt.details.isEmpty()) {
                    sb.append("    Details: ").append(attempt.details).append("\n");
                }
            }
        }

        if (testName != null) {
            int remaining = MAX_ATTEMPTS_PER_TEST - getAttemptCount(testName);
            sb.append("\nRemaining attempts for ").append(testName).append(": ").append(remaining).append("\n");
        }

        if (!unreparableTests.isEmpty()) {
            sb.append("\nTests marked as unreparable (max attempts reached):\n");
            for (String test : unreparableTests) {
                sb.append("  - ").append(test).append("\n");
            }
        }

        sb.append("\n======================\n");
        return sb.toString();
    }

    /**
     * Gets suggestions based on repair history.
     *
     * @param testName The test to get suggestions for
     * @return Suggestions string
     */
    public String getRepairSuggestions(String testName) {
        List<RepairAttempt> testAttempts = getAttemptsForTest(testName);

        if (testAttempts.isEmpty()) {
            return "No previous attempts. Try standard repair approaches.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Based on previous attempts, avoid:\n");

        Set<String> triedActions = new HashSet<>();
        Set<TestReportTool.FailureType> failedTypes = new HashSet<>();

        for (RepairAttempt attempt : testAttempts) {
            if (attempt.result != RepairResult.SUCCESS) {
                triedActions.add(attempt.repairAction);
                failedTypes.add(attempt.failureType);
            }
        }

        for (String action : triedActions) {
            sb.append("  ✗ ").append(action).append(" (already tried)\n");
        }

        // Suggest alternative approaches
        sb.append("\nSuggested alternatives:\n");
        if (failedTypes.contains(TestReportTool.FailureType.ASSERTION_FAILURE)) {
            sb.append("  • Re-analyze the source code logic more carefully\n");
            sb.append("  • Check if the expected value calculation is correct\n");
        }
        if (failedTypes.contains(TestReportTool.FailureType.NULL_POINTER)) {
            sb.append("  • Verify ALL mocks are properly initialized\n");
            sb.append("  • Check for missing when().thenReturn() stubs\n");
        }
        if (failedTypes.contains(TestReportTool.FailureType.MOCK_ERROR)) {
            sb.append("  • Review method signatures match exactly\n");
            sb.append("  • Consider using any() matchers instead of exact values\n");
        }

        return sb.toString();
    }

    /**
     * Clears all repair history.
     */
    public void clear() {
        attempts.clear();
        attemptCountByTest.clear();
        unreparableTests.clear();
        log.info("Repair tracker cleared");
    }

    /**
     * Gets overall repair statistics.
     */
    public String getStatistics() {
        if (attempts.isEmpty()) {
            return "No repair attempts recorded.";
        }

        long successCount = attempts.stream().filter(a -> a.result == RepairResult.SUCCESS).count();
        long failedCount = attempts.stream().filter(a -> a.result == RepairResult.FAILED).count();
        long partialCount = attempts.stream().filter(a -> a.result == RepairResult.PARTIAL).count();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Repair Statistics ===\n");
        sb.append(String.format("Total Attempts: %d\n", attempts.size()));
        sb.append(String.format("Successful: %d (%.0f%%)\n", successCount, 
                attempts.isEmpty() ? 0 : successCount * 100.0 / attempts.size()));
        sb.append(String.format("Partial: %d\n", partialCount));
        sb.append(String.format("Failed: %d\n", failedCount));
        sb.append(String.format("Tests Attempted: %d\n", attemptCountByTest.size()));
        sb.append(String.format("Unreparable Tests: %d\n", unreparableTests.size()));
        sb.append("=========================\n");

        return sb.toString();
    }
}
