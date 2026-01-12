package com.codelogickeep.agent.ut.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tool for parsing and analyzing Maven Surefire test reports.
 * Provides structured error information to help the Agent understand test failures.
 */
public class TestReportTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(TestReportTool.class);

    /**
     * Represents a test failure with structured information.
     */
    public record TestFailure(
            String testClass,
            String testMethod,
            FailureType failureType,
            String message,
            String stackTrace,
            String expected,
            String actual
    ) {
        public String toSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Test: ").append(testClass).append(".").append(testMethod).append("\n");
            sb.append("Type: ").append(failureType.getDescription()).append("\n");
            sb.append("Message: ").append(message != null ? message : "N/A").append("\n");
            if (expected != null && actual != null) {
                sb.append("Expected: ").append(expected).append("\n");
                sb.append("Actual: ").append(actual).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * Types of test failures with repair suggestions.
     */
    public enum FailureType {
        COMPILATION_ERROR("Compilation Error", 
            "Check for syntax errors, missing imports, or type mismatches. Use analyzeClass to verify correct types."),
        ASSERTION_FAILURE("Assertion Failure", 
            "The test assertion failed. Compare expected vs actual values and review the business logic."),
        NULL_POINTER("NullPointerException", 
            "A null value was encountered. Ensure mocks are properly initialized with @Mock and @InjectMocks."),
        MOCK_ERROR("Mock Configuration Error", 
            "Mock setup issue. Verify when().thenReturn() stubs match the method signatures."),
        TIMEOUT("Test Timeout", 
            "The test exceeded the time limit. Check for infinite loops or blocking calls."),
        DEPENDENCY_ERROR("Dependency/ClassNotFound Error", 
            "A required class or dependency is missing. Check imports and pom.xml dependencies."),
        INITIALIZATION_ERROR("Initialization Error", 
            "Failed to initialize test or class under test. Check constructors and @BeforeEach setup."),
        UNKNOWN("Unknown Error", 
            "Analyze the stack trace for more details.");

        private final String description;
        private final String repairSuggestion;

        FailureType(String description, String repairSuggestion) {
            this.description = description;
            this.repairSuggestion = repairSuggestion;
        }

        public String getDescription() {
            return description;
        }

        public String getRepairSuggestion() {
            return repairSuggestion;
        }
    }

    @Tool("Get a summary of test results from the latest Surefire reports")
    public String getTestResultsSummary(
            @P("Path to the project root directory") String projectPath
    ) throws IOException {
        log.info("Tool Input - getTestResultsSummary: projectPath={}", projectPath);

        Path reportsDir = Paths.get(projectPath, "target", "surefire-reports");
        if (!Files.exists(reportsDir)) {
            String result = "No Surefire reports found at: " + reportsDir + 
                           "\nRun 'mvn test' or use executeTest tool first.";
            log.info("Tool Output - getTestResultsSummary: {}", result);
            return result;
        }

        List<File> xmlReports;
        try (Stream<Path> stream = Files.list(reportsDir)) {
            xmlReports = stream
                    .filter(p -> p.toString().endsWith(".xml"))
                    .filter(p -> p.getFileName().toString().startsWith("TEST-"))
                    .map(Path::toFile)
                    .collect(Collectors.toList());
        }

        if (xmlReports.isEmpty()) {
            String result = "No XML test reports found in: " + reportsDir;
            log.info("Tool Output - getTestResultsSummary: {}", result);
            return result;
        }

        int totalTests = 0;
        int totalFailures = 0;
        int totalErrors = 0;
        int totalSkipped = 0;
        double totalTime = 0;
        List<String> failedTests = new ArrayList<>();

        for (File report : xmlReports) {
            try {
                Document doc = parseXml(report);
                Element root = doc.getDocumentElement();

                totalTests += Integer.parseInt(root.getAttribute("tests"));
                totalFailures += Integer.parseInt(root.getAttribute("failures"));
                totalErrors += Integer.parseInt(root.getAttribute("errors"));
                totalSkipped += Integer.parseInt(root.getAttribute("skipped"));
                totalTime += Double.parseDouble(root.getAttribute("time"));

                // Collect failed test names
                NodeList testcases = root.getElementsByTagName("testcase");
                for (int i = 0; i < testcases.getLength(); i++) {
                    Element testcase = (Element) testcases.item(i);
                    if (testcase.getElementsByTagName("failure").getLength() > 0 ||
                        testcase.getElementsByTagName("error").getLength() > 0) {
                        String className = testcase.getAttribute("classname");
                        String methodName = testcase.getAttribute("name");
                        failedTests.add(className + "." + methodName);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse report: {}", report, e);
            }
        }

        StringBuilder result = new StringBuilder();
        result.append("=== Test Results Summary ===\n");
        result.append(String.format("Total Tests: %d\n", totalTests));
        result.append(String.format("Passed: %d\n", totalTests - totalFailures - totalErrors - totalSkipped));
        result.append(String.format("Failures: %d\n", totalFailures));
        result.append(String.format("Errors: %d\n", totalErrors));
        result.append(String.format("Skipped: %d\n", totalSkipped));
        result.append(String.format("Time: %.2fs\n", totalTime));

        if (totalFailures + totalErrors == 0) {
            result.append("\n✓ ALL TESTS PASSED\n");
        } else {
            result.append("\n✗ FAILED TESTS:\n");
            for (String test : failedTests) {
                result.append("  - ").append(test).append("\n");
            }
        }
        result.append("============================\n");

        String finalResult = result.toString();
        log.info("Tool Output - getTestResultsSummary: tests={}, failures={}", totalTests, totalFailures + totalErrors);
        return finalResult;
    }

    @Tool("Analyze test failures in detail and get repair suggestions")
    public String analyzeTestFailures(
            @P("Path to the project root directory") String projectPath
    ) throws IOException {
        log.info("Tool Input - analyzeTestFailures: projectPath={}", projectPath);

        Path reportsDir = Paths.get(projectPath, "target", "surefire-reports");
        if (!Files.exists(reportsDir)) {
            return "No Surefire reports found. Run tests first.";
        }

        List<TestFailure> failures = collectFailures(reportsDir);

        if (failures.isEmpty()) {
            String result = "No test failures found. All tests passed!";
            log.info("Tool Output - analyzeTestFailures: {}", result);
            return result;
        }

        StringBuilder result = new StringBuilder();
        result.append("=== Test Failure Analysis ===\n\n");
        result.append("Found ").append(failures.size()).append(" failure(s):\n\n");

        for (int i = 0; i < failures.size(); i++) {
            TestFailure failure = failures.get(i);
            result.append("─".repeat(50)).append("\n");
            result.append("Failure #").append(i + 1).append("\n");
            result.append("─".repeat(50)).append("\n");
            result.append(failure.toSummary());
            result.append("\n💡 Repair Suggestion:\n");
            result.append("   ").append(failure.failureType.getRepairSuggestion()).append("\n");

            // Show relevant stack trace (first 5 lines)
            if (failure.stackTrace != null && !failure.stackTrace.isEmpty()) {
                result.append("\nStack Trace (first 5 lines):\n");
                String[] lines = failure.stackTrace.split("\n");
                for (int j = 0; j < Math.min(5, lines.length); j++) {
                    result.append("   ").append(lines[j].trim()).append("\n");
                }
            }
            result.append("\n");
        }

        result.append("=============================\n");

        String finalResult = result.toString();
        log.info("Tool Output - analyzeTestFailures: {} failures analyzed", failures.size());
        return finalResult;
    }

    @Tool("Get detailed failure information for a specific test class")
    public String getTestClassFailures(
            @P("Path to the project root directory") String projectPath,
            @P("Fully qualified test class name (e.g., com.example.MyServiceTest)") String testClassName
    ) throws IOException {
        log.info("Tool Input - getTestClassFailures: projectPath={}, testClassName={}", projectPath, testClassName);

        Path reportFile = Paths.get(projectPath, "target", "surefire-reports", "TEST-" + testClassName + ".xml");
        if (!Files.exists(reportFile)) {
            String result = "No report found for test class: " + testClassName + 
                           "\nExpected at: " + reportFile;
            log.info("Tool Output - getTestClassFailures: {}", result);
            return result;
        }

        try {
            Document doc = parseXml(reportFile.toFile());
            Element root = doc.getDocumentElement();

            StringBuilder result = new StringBuilder();
            result.append("=== Test Class Report: ").append(testClassName).append(" ===\n\n");

            int tests = Integer.parseInt(root.getAttribute("tests"));
            int failures = Integer.parseInt(root.getAttribute("failures"));
            int errors = Integer.parseInt(root.getAttribute("errors"));
            double time = Double.parseDouble(root.getAttribute("time"));

            result.append(String.format("Tests: %d | Failures: %d | Errors: %d | Time: %.2fs\n\n", 
                    tests, failures, errors, time));

            NodeList testcases = root.getElementsByTagName("testcase");
            for (int i = 0; i < testcases.getLength(); i++) {
                Element testcase = (Element) testcases.item(i);
                String methodName = testcase.getAttribute("name");
                String methodTime = testcase.getAttribute("time");

                NodeList failureNodes = testcase.getElementsByTagName("failure");
                NodeList errorNodes = testcase.getElementsByTagName("error");

                if (failureNodes.getLength() > 0) {
                    Element failure = (Element) failureNodes.item(0);
                    result.append("✗ ").append(methodName).append(" (").append(methodTime).append("s)\n");
                    result.append("  Type: FAILURE\n");
                    result.append("  Message: ").append(failure.getAttribute("message")).append("\n");
                    result.append("  ").append(extractRelevantStackLine(failure.getTextContent())).append("\n\n");
                } else if (errorNodes.getLength() > 0) {
                    Element error = (Element) errorNodes.item(0);
                    result.append("✗ ").append(methodName).append(" (").append(methodTime).append("s)\n");
                    result.append("  Type: ERROR\n");
                    result.append("  Message: ").append(error.getAttribute("message")).append("\n");
                    result.append("  ").append(extractRelevantStackLine(error.getTextContent())).append("\n\n");
                } else {
                    result.append("✓ ").append(methodName).append(" (").append(methodTime).append("s)\n");
                }
            }

            result.append("==========================================\n");

            String finalResult = result.toString();
            log.info("Tool Output - getTestClassFailures: {} tests, {} failures", tests, failures + errors);
            return finalResult;

        } catch (Exception e) {
            throw new IOException("Failed to parse test report: " + e.getMessage(), e);
        }
    }

    @Tool("Get repair recommendations based on failure type")
    public String getRepairRecommendations(
            @P("Path to the project root directory") String projectPath
    ) throws IOException {
        log.info("Tool Input - getRepairRecommendations: projectPath={}", projectPath);

        Path reportsDir = Paths.get(projectPath, "target", "surefire-reports");
        if (!Files.exists(reportsDir)) {
            return "No test reports found. Run tests first.";
        }

        List<TestFailure> failures = collectFailures(reportsDir);

        if (failures.isEmpty()) {
            return "No failures to repair. All tests passed!";
        }

        // Group failures by type
        java.util.Map<FailureType, List<TestFailure>> byType = failures.stream()
                .collect(Collectors.groupingBy(TestFailure::failureType));

        StringBuilder result = new StringBuilder();
        result.append("=== Repair Recommendations ===\n\n");

        // Priority order for repair
        FailureType[] priority = {
                FailureType.COMPILATION_ERROR,
                FailureType.DEPENDENCY_ERROR,
                FailureType.NULL_POINTER,
                FailureType.MOCK_ERROR,
                FailureType.INITIALIZATION_ERROR,
                FailureType.ASSERTION_FAILURE,
                FailureType.TIMEOUT,
                FailureType.UNKNOWN
        };

        int step = 1;
        for (FailureType type : priority) {
            List<TestFailure> typeFailures = byType.get(type);
            if (typeFailures != null && !typeFailures.isEmpty()) {
                result.append("Step ").append(step++).append(": Fix ").append(type.getDescription());
                result.append(" (").append(typeFailures.size()).append(" issue(s))\n");
                result.append("   ➤ ").append(type.getRepairSuggestion()).append("\n");
                result.append("   Affected tests:\n");
                for (TestFailure f : typeFailures) {
                    result.append("     - ").append(f.testClass).append(".").append(f.testMethod).append("\n");
                }
                result.append("\n");
            }
        }

        result.append("Recommended repair order: Fix compilation/dependency errors first,\n");
        result.append("then mock issues, finally assertion failures.\n");
        result.append("==============================\n");

        String finalResult = result.toString();
        log.info("Tool Output - getRepairRecommendations: {} total failures", failures.size());
        return finalResult;
    }

    private List<TestFailure> collectFailures(Path reportsDir) throws IOException {
        List<TestFailure> failures = new ArrayList<>();

        try (Stream<Path> stream = Files.list(reportsDir)) {
            List<File> xmlReports = stream
                    .filter(p -> p.toString().endsWith(".xml"))
                    .filter(p -> p.getFileName().toString().startsWith("TEST-"))
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            for (File report : xmlReports) {
                try {
                    Document doc = parseXml(report);
                    Element root = doc.getDocumentElement();
                    String className = root.getAttribute("name");

                    NodeList testcases = root.getElementsByTagName("testcase");
                    for (int i = 0; i < testcases.getLength(); i++) {
                        Element testcase = (Element) testcases.item(i);
                        String methodName = testcase.getAttribute("name");

                        NodeList failureNodes = testcase.getElementsByTagName("failure");
                        NodeList errorNodes = testcase.getElementsByTagName("error");

                        if (failureNodes.getLength() > 0) {
                            Element failure = (Element) failureNodes.item(0);
                            failures.add(parseFailure(className, methodName, failure, false));
                        } else if (errorNodes.getLength() > 0) {
                            Element error = (Element) errorNodes.item(0);
                            failures.add(parseFailure(className, methodName, error, true));
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse report: {}", report, e);
                }
            }
        }

        return failures;
    }

    private TestFailure parseFailure(String className, String methodName, Element element, boolean isError) {
        String message = element.getAttribute("message");
        String type = element.getAttribute("type");
        String stackTrace = element.getTextContent();

        FailureType failureType = classifyFailure(type, message, stackTrace, isError);

        // Try to extract expected/actual for assertion failures
        String expected = null;
        String actual = null;
        if (failureType == FailureType.ASSERTION_FAILURE && message != null) {
            // Common pattern: "expected: <X> but was: <Y>"
            if (message.contains("expected:") && message.contains("but was:")) {
                int expStart = message.indexOf("expected:") + 9;
                int expEnd = message.indexOf("but was:");
                if (expStart < expEnd) {
                    expected = message.substring(expStart, expEnd).trim();
                    actual = message.substring(expEnd + 8).trim();
                }
            }
        }

        return new TestFailure(className, methodName, failureType, message, stackTrace, expected, actual);
    }

    private FailureType classifyFailure(String type, String message, String stackTrace, boolean isError) {
        String combined = ((type != null ? type : "") + " " + 
                          (message != null ? message : "") + " " + 
                          (stackTrace != null ? stackTrace : "")).toLowerCase();

        if (combined.contains("compilationerror") || combined.contains("cannot find symbol") ||
            combined.contains("cannot resolve") || combined.contains("syntax error")) {
            return FailureType.COMPILATION_ERROR;
        }

        if (combined.contains("nullpointerexception")) {
            return FailureType.NULL_POINTER;
        }

        if (combined.contains("classnotfoundexception") || combined.contains("noclassdeffounderror") ||
            combined.contains("nosuchmethod") || combined.contains("dependency")) {
            return FailureType.DEPENDENCY_ERROR;
        }

        if (combined.contains("mock") || combined.contains("stubbing") || 
            combined.contains("wanted but not invoked") || combined.contains("misplacedmatcherexception")) {
            return FailureType.MOCK_ERROR;
        }

        if (combined.contains("timeout") || combined.contains("timed out")) {
            return FailureType.TIMEOUT;
        }

        if (combined.contains("initializationerror") || combined.contains("beforeeach") ||
            combined.contains("beforeall") || combined.contains("instantiation")) {
            return FailureType.INITIALIZATION_ERROR;
        }

        if (combined.contains("assertionfailederror") || combined.contains("expected:") ||
            combined.contains("assertequals") || combined.contains("asserttrue") ||
            combined.contains("but was:") || !isError) {
            return FailureType.ASSERTION_FAILURE;
        }

        return FailureType.UNKNOWN;
    }

    private String extractRelevantStackLine(String stackTrace) {
        if (stackTrace == null || stackTrace.isEmpty()) {
            return "";
        }

        String[] lines = stackTrace.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            // Find first line that's in user code (not framework)
            if (trimmed.startsWith("at ") && 
                !trimmed.contains("junit") && 
                !trimmed.contains("mockito") &&
                !trimmed.contains("java.base") &&
                !trimmed.contains("sun.reflect")) {
                return trimmed;
            }
        }
        return lines.length > 0 ? lines[0].trim() : "";
    }

    private Document parseXml(File xmlFile) throws Exception {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(xmlFile);
        doc.getDocumentElement().normalize();
        return doc;
    }
}
