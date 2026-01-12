package com.codelogickeep.agent.ut.tools;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tool for analyzing existing test code style and extracting project-specific testing patterns.
 * Helps the Agent generate tests that match the project's conventions.
 */
public class StyleAnalyzerTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(StyleAnalyzerTool.class);

    /**
     * Represents the extracted test style from a project.
     */
    public record TestStyle(
            String assertionLibrary,          // JUnit, AssertJ, Hamcrest
            String mockingFramework,          // Mockito, EasyMock, etc.
            String namingConvention,          // should_xxx, test_xxx, xxx_test
            boolean usesBddStyle,             // given/when/then
            boolean usesDisplayName,          // @DisplayName annotation
            boolean usesNestedTests,          // @Nested annotation
            List<String> commonAnnotations,   // Common test annotations used
            List<String> commonImports,       // Commonly used imports
            String setupMethod,               // @BeforeEach, @Before, setUp()
            String teardownMethod             // @AfterEach, @After
    ) {
        public String toSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Project Test Style ===\n");
            sb.append("Assertion Library: ").append(assertionLibrary).append("\n");
            sb.append("Mocking Framework: ").append(mockingFramework).append("\n");
            sb.append("Naming Convention: ").append(namingConvention).append("\n");
            sb.append("Uses BDD Style: ").append(usesBddStyle).append("\n");
            sb.append("Uses @DisplayName: ").append(usesDisplayName).append("\n");
            sb.append("Uses @Nested: ").append(usesNestedTests).append("\n");
            sb.append("Setup Method: ").append(setupMethod).append("\n");
            if (!commonAnnotations.isEmpty()) {
                sb.append("Common Annotations: ").append(String.join(", ", commonAnnotations)).append("\n");
            }
            sb.append("==========================\n");
            return sb.toString();
        }
    }

    @Tool("Analyze existing test classes in the project to extract testing style and conventions")
    public String analyzeProjectTestStyle(
            @P("Path to the project root directory") String projectPath
    ) throws IOException {
        log.info("Tool Input - analyzeProjectTestStyle: projectPath={}", projectPath);

        Path testDir = Paths.get(projectPath, "src", "test", "java");
        if (!Files.exists(testDir)) {
            String result = "No test directory found at: " + testDir + ". Cannot analyze test style.";
            log.info("Tool Output - analyzeProjectTestStyle: {}", result);
            return result;
        }

        List<Path> testFiles;
        try (Stream<Path> stream = Files.find(testDir, 20,
                (p, attr) -> attr.isRegularFile() && p.toString().endsWith("Test.java"))) {
            testFiles = stream.limit(20).collect(Collectors.toList()); // Analyze up to 20 test files
        }

        if (testFiles.isEmpty()) {
            String result = "No test files (*Test.java) found in: " + testDir;
            log.info("Tool Output - analyzeProjectTestStyle: {}", result);
            return result;
        }

        TestStyle style = analyzeTestFiles(testFiles);
        String result = style.toSummary();
        log.info("Tool Output - analyzeProjectTestStyle: analyzed {} files", testFiles.size());
        return result;
    }

    @Tool("Analyze a single test class to extract its testing patterns")
    public String analyzeTestClass(
            @P("Path to the test Java file") String testFilePath
    ) throws IOException {
        log.info("Tool Input - analyzeTestClass: testFilePath={}", testFilePath);

        Path path = Paths.get(testFilePath);
        if (!Files.exists(path)) {
            String result = "Test file not found: " + testFilePath;
            log.info("Tool Output - analyzeTestClass: {}", result);
            return result;
        }

        CompilationUnit cu = StaticJavaParser.parse(path);
        TestFileAnalysis analysis = analyzeTestFile(cu);

        StringBuilder result = new StringBuilder();
        result.append("=== Test Class Analysis: ").append(path.getFileName()).append(" ===\n\n");

        result.append("Imports:\n");
        for (String imp : analysis.imports) {
            result.append("  - ").append(imp).append("\n");
        }

        result.append("\nAnnotations Used:\n");
        for (String ann : analysis.annotations) {
            result.append("  - @").append(ann).append("\n");
        }

        result.append("\nAssertion Methods:\n");
        for (String assertion : analysis.assertionMethods) {
            result.append("  - ").append(assertion).append("\n");
        }

        result.append("\nMock Methods:\n");
        for (String mock : analysis.mockMethods) {
            result.append("  - ").append(mock).append("\n");
        }

        result.append("\nTest Method Names:\n");
        for (String methodName : analysis.testMethodNames) {
            result.append("  - ").append(methodName).append("\n");
        }

        result.append("\nNaming Pattern: ").append(detectNamingPattern(analysis.testMethodNames)).append("\n");

        String finalResult = result.toString();
        log.info("Tool Output - analyzeTestClass: length={}", finalResult.length());
        return finalResult;
    }

    @Tool("Get recommended test style guidelines for generating new tests based on project analysis")
    public String getTestStyleGuidelines(
            @P("Path to the project root directory") String projectPath
    ) throws IOException {
        log.info("Tool Input - getTestStyleGuidelines: projectPath={}", projectPath);

        Path testDir = Paths.get(projectPath, "src", "test", "java");
        List<Path> testFiles = new ArrayList<>();

        if (Files.exists(testDir)) {
            try (Stream<Path> stream = Files.find(testDir, 20,
                    (p, attr) -> attr.isRegularFile() && p.toString().endsWith("Test.java"))) {
                testFiles = stream.limit(10).collect(Collectors.toList());
            }
        }

        StringBuilder guidelines = new StringBuilder();
        guidelines.append("=== Test Style Guidelines ===\n\n");

        if (testFiles.isEmpty()) {
            // Provide default guidelines
            guidelines.append("No existing tests found. Using default JUnit 5 + Mockito conventions:\n\n");
            guidelines.append("1. Use @ExtendWith(MockitoExtension.class) for test classes\n");
            guidelines.append("2. Use @Mock for dependencies, @InjectMocks for the class under test\n");
            guidelines.append("3. Use Assertions.assertEquals(), assertThrows(), etc.\n");
            guidelines.append("4. Method naming: should_ReturnX_When_ConditionY()\n");
            guidelines.append("5. Use @DisplayName for readable test descriptions\n");
            guidelines.append("6. Use @BeforeEach for setup\n");
        } else {
            TestStyle style = analyzeTestFiles(testFiles);
            guidelines.append("Based on analysis of ").append(testFiles.size()).append(" existing test files:\n\n");

            // Assertion style
            guidelines.append("1. Assertion Style:\n");
            if ("AssertJ".equals(style.assertionLibrary)) {
                guidelines.append("   Use AssertJ fluent assertions: assertThat(result).isEqualTo(expected)\n");
            } else if ("Hamcrest".equals(style.assertionLibrary)) {
                guidelines.append("   Use Hamcrest matchers: assertThat(result, is(expected))\n");
            } else {
                guidelines.append("   Use JUnit assertions: assertEquals(expected, result)\n");
            }

            // Mock style
            guidelines.append("\n2. Mocking Style:\n");
            if ("Mockito".equals(style.mockingFramework)) {
                guidelines.append("   Use @Mock and @InjectMocks annotations\n");
                guidelines.append("   Use when().thenReturn() for stubbing\n");
                guidelines.append("   Use verify() for interaction verification\n");
            }

            // Naming style
            guidelines.append("\n3. Method Naming:\n");
            guidelines.append("   Pattern: ").append(style.namingConvention).append("\n");

            // Additional conventions
            if (style.usesDisplayName) {
                guidelines.append("\n4. Use @DisplayName for human-readable test descriptions\n");
            }
            if (style.usesNestedTests) {
                guidelines.append("\n5. Consider using @Nested for grouping related tests\n");
            }
            if (style.usesBddStyle) {
                guidelines.append("\n6. Follow BDD style: given/when/then comments in test methods\n");
            }

            // Setup
            guidelines.append("\n7. Setup:\n");
            guidelines.append("   ").append(style.setupMethod).append("\n");
        }

        guidelines.append("\n==============================\n");

        String result = guidelines.toString();
        log.info("Tool Output - getTestStyleGuidelines: length={}", result.length());
        return result;
    }

    private TestStyle analyzeTestFiles(List<Path> testFiles) {
        Map<String, Integer> assertionLibCounts = new HashMap<>();
        Map<String, Integer> mockingFrameworkCounts = new HashMap<>();
        Map<String, Integer> namingPatternCounts = new HashMap<>();
        Set<String> allAnnotations = new HashSet<>();
        Set<String> allImports = new HashSet<>();
        int bddCount = 0;
        int displayNameCount = 0;
        int nestedCount = 0;
        String setupMethod = "@BeforeEach";
        String teardownMethod = "@AfterEach";

        for (Path testFile : testFiles) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(testFile);
                TestFileAnalysis analysis = analyzeTestFile(cu);

                // Count assertion library
                if (analysis.imports.stream().anyMatch(i -> i.contains("assertj"))) {
                    assertionLibCounts.merge("AssertJ", 1, Integer::sum);
                } else if (analysis.imports.stream().anyMatch(i -> i.contains("hamcrest"))) {
                    assertionLibCounts.merge("Hamcrest", 1, Integer::sum);
                } else {
                    assertionLibCounts.merge("JUnit", 1, Integer::sum);
                }

                // Count mocking framework
                if (analysis.imports.stream().anyMatch(i -> i.contains("mockito"))) {
                    mockingFrameworkCounts.merge("Mockito", 1, Integer::sum);
                } else if (analysis.imports.stream().anyMatch(i -> i.contains("easymock"))) {
                    mockingFrameworkCounts.merge("EasyMock", 1, Integer::sum);
                }

                // Analyze naming pattern
                String pattern = detectNamingPattern(analysis.testMethodNames);
                namingPatternCounts.merge(pattern, 1, Integer::sum);

                // Check for BDD style
                if (analysis.hasBddComments) {
                    bddCount++;
                }

                // Check for @DisplayName
                if (analysis.annotations.contains("DisplayName")) {
                    displayNameCount++;
                }

                // Check for @Nested
                if (analysis.annotations.contains("Nested")) {
                    nestedCount++;
                }

                allAnnotations.addAll(analysis.annotations);
                allImports.addAll(analysis.imports);

                // Check setup method
                if (analysis.annotations.contains("Before")) {
                    setupMethod = "@Before (JUnit 4)";
                }

            } catch (Exception e) {
                log.warn("Failed to analyze test file: {}", testFile, e);
            }
        }

        // Determine majority patterns
        String assertionLib = getMostCommon(assertionLibCounts, "JUnit");
        String mockingFramework = getMostCommon(mockingFrameworkCounts, "Mockito");
        String namingConvention = getMostCommon(namingPatternCounts, "should_xxx_when_yyy");

        return new TestStyle(
                assertionLib,
                mockingFramework,
                namingConvention,
                bddCount > testFiles.size() / 2,
                displayNameCount > testFiles.size() / 2,
                nestedCount > 0,
                new ArrayList<>(allAnnotations),
                new ArrayList<>(allImports.stream().limit(10).toList()),
                setupMethod,
                teardownMethod
        );
    }

    private TestFileAnalysis analyzeTestFile(CompilationUnit cu) {
        TestFileAnalysis analysis = new TestFileAnalysis();

        // Extract imports
        for (ImportDeclaration imp : cu.getImports()) {
            analysis.imports.add(imp.getNameAsString());
        }

        cu.findFirst(ClassOrInterfaceDeclaration.class).ifPresent(clazz -> {
            // Extract class-level annotations
            for (AnnotationExpr ann : clazz.getAnnotations()) {
                analysis.annotations.add(ann.getNameAsString());
            }

            // Extract field annotations
            for (FieldDeclaration field : clazz.getFields()) {
                for (AnnotationExpr ann : field.getAnnotations()) {
                    analysis.annotations.add(ann.getNameAsString());
                }
            }

            // Extract method info
            for (MethodDeclaration method : clazz.getMethods()) {
                for (AnnotationExpr ann : method.getAnnotations()) {
                    String annName = ann.getNameAsString();
                    analysis.annotations.add(annName);

                    if (annName.equals("Test") || annName.equals("ParameterizedTest")) {
                        analysis.testMethodNames.add(method.getNameAsString());
                    }
                }

                // Check for BDD comments
                method.getBody().ifPresent(body -> {
                    String bodyStr = body.toString().toLowerCase();
                    if (bodyStr.contains("// given") || bodyStr.contains("// when") || bodyStr.contains("// then")) {
                        analysis.hasBddComments = true;
                    }
                });

                // Extract method calls
                method.accept(new VoidVisitorAdapter<Void>() {
                    @Override
                    public void visit(MethodCallExpr n, Void arg) {
                        super.visit(n, arg);
                        String methodName = n.getNameAsString();
                        if (isAssertionMethod(methodName)) {
                            analysis.assertionMethods.add(methodName);
                        }
                        if (isMockMethod(methodName)) {
                            analysis.mockMethods.add(methodName);
                        }
                    }
                }, null);
            }
        });

        return analysis;
    }

    private String detectNamingPattern(List<String> methodNames) {
        if (methodNames.isEmpty()) {
            return "unknown";
        }

        int shouldCount = 0;
        int testPrefixCount = 0;
        int underscoreCount = 0;
        int camelCaseCount = 0;

        for (String name : methodNames) {
            if (name.startsWith("should")) {
                shouldCount++;
            }
            if (name.startsWith("test")) {
                testPrefixCount++;
            }
            if (name.contains("_")) {
                underscoreCount++;
            } else {
                camelCaseCount++;
            }
        }

        // Determine primary pattern
        if (shouldCount > methodNames.size() / 2) {
            if (underscoreCount > camelCaseCount) {
                return "should_DoX_When_Y (snake_case BDD)";
            }
            return "shouldDoXWhenY (camelCase BDD)";
        }
        if (testPrefixCount > methodNames.size() / 2) {
            return "testMethodName (traditional)";
        }
        if (underscoreCount > camelCaseCount) {
            return "method_condition_result (snake_case)";
        }
        return "methodNameDescriptive (camelCase)";
    }

    private boolean isAssertionMethod(String methodName) {
        return methodName.startsWith("assert") ||
               methodName.equals("assertThat") ||
               methodName.equals("fail") ||
               methodName.equals("assertTrue") ||
               methodName.equals("assertFalse") ||
               methodName.equals("assertEquals") ||
               methodName.equals("assertNotNull") ||
               methodName.equals("assertNull") ||
               methodName.equals("assertThrows") ||
               methodName.equals("isEqualTo") ||
               methodName.equals("isNotNull") ||
               methodName.equals("hasSize") ||
               methodName.equals("contains");
    }

    private boolean isMockMethod(String methodName) {
        return methodName.equals("mock") ||
               methodName.equals("when") ||
               methodName.equals("thenReturn") ||
               methodName.equals("thenThrow") ||
               methodName.equals("verify") ||
               methodName.equals("doReturn") ||
               methodName.equals("doThrow") ||
               methodName.equals("any") ||
               methodName.equals("eq") ||
               methodName.equals("spy");
    }

    private String getMostCommon(Map<String, Integer> counts, String defaultValue) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(defaultValue);
    }

    private static class TestFileAnalysis {
        List<String> imports = new ArrayList<>();
        Set<String> annotations = new HashSet<>();
        List<String> testMethodNames = new ArrayList<>();
        Set<String> assertionMethods = new HashSet<>();
        Set<String> mockMethods = new HashSet<>();
        boolean hasBddComments = false;
    }
}
