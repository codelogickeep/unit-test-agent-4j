package com.codelogickeep.agent.ut.tools;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TestDiscoveryTool.
 */
@DisplayName("TestDiscoveryTool Tests")
class TestDiscoveryToolTest {

    private TestDiscoveryTool testDiscoveryTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        testDiscoveryTool = new TestDiscoveryTool();
    }

    @Nested
    @DisplayName("findTestClasses Tests")
    class FindTestClassesTests {

        @Test
        @DisplayName("Should return error for non-Java files")
        void shouldReturnErrorForNonJavaFiles() throws IOException {
            // Given
            String nonJavaFile = "/path/to/file.txt";

            // When
            String result = testDiscoveryTool.findTestClasses(nonJavaFile, tempDir.toString());

            // Then
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("Not a Java file"));
        }

        @Test
        @DisplayName("Should find test class with Test suffix")
        void shouldFindTestWithTestSuffix() throws IOException {
            // Given - create project structure
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("UserService.java");
            Files.writeString(sourceFile, "public class UserService {}");
            Files.writeString(srcTestJava.resolve("UserServiceTest.java"), "public class UserServiceTest {}");

            // When
            String result = testDiscoveryTool.findTestClasses(sourceFile.toString(), tempDir.toString());

            // Then
            assertTrue(result.contains("Found 1 test class"));
            assertTrue(result.contains("UserServiceTest.java"));
        }

        @Test
        @DisplayName("Should find test class with Tests suffix")
        void shouldFindTestWithTestsSuffix() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("OrderService.java");
            Files.writeString(sourceFile, "public class OrderService {}");
            Files.writeString(srcTestJava.resolve("OrderServiceTests.java"), "public class OrderServiceTests {}");

            // When
            String result = testDiscoveryTool.findTestClasses(sourceFile.toString(), tempDir.toString());

            // Then
            assertTrue(result.contains("Found 1 test class"));
            assertTrue(result.contains("OrderServiceTests.java"));
        }

        @Test
        @DisplayName("Should find test class with TestCase suffix")
        void shouldFindTestWithTestCaseSuffix() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("Calculator.java");
            Files.writeString(sourceFile, "public class Calculator {}");
            Files.writeString(srcTestJava.resolve("CalculatorTestCase.java"), "public class CalculatorTestCase {}");

            // When
            String result = testDiscoveryTool.findTestClasses(sourceFile.toString(), tempDir.toString());

            // Then
            assertTrue(result.contains("Found 1 test class"));
            assertTrue(result.contains("CalculatorTestCase.java"));
        }

        @Test
        @DisplayName("Should find test class with Test prefix")
        void shouldFindTestWithTestPrefix() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("Validator.java");
            Files.writeString(sourceFile, "public class Validator {}");
            Files.writeString(srcTestJava.resolve("TestValidator.java"), "public class TestValidator {}");

            // When
            String result = testDiscoveryTool.findTestClasses(sourceFile.toString(), tempDir.toString());

            // Then
            assertTrue(result.contains("Found 1 test class"));
            assertTrue(result.contains("TestValidator.java"));
        }

        @Test
        @DisplayName("Should find multiple test classes")
        void shouldFindMultipleTestClasses() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("UserService.java");
            Files.writeString(sourceFile, "public class UserService {}");
            Files.writeString(srcTestJava.resolve("UserServiceTest.java"), "");
            Files.writeString(srcTestJava.resolve("UserServiceTests.java"), "");
            Files.writeString(srcTestJava.resolve("UserServiceTestCase.java"), "");

            // When
            String result = testDiscoveryTool.findTestClasses(sourceFile.toString(), tempDir.toString());

            // Then
            assertTrue(result.contains("Found 3 test class"));
        }

        @Test
        @DisplayName("Should return message when no test classes found")
        void shouldReturnMessageWhenNoTestClassesFound() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("NoTestService.java");
            Files.writeString(sourceFile, "public class NoTestService {}");
            // Don't create any test file

            // When
            String result = testDiscoveryTool.findTestClasses(sourceFile.toString(), tempDir.toString());

            // Then
            assertTrue(result.contains("No existing test classes found"));
            assertTrue(result.contains("NoTestService"));
        }

        @Test
        @DisplayName("Should find test classes containing source class name")
        void shouldFindTestClassesContainingSourceClassName() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("User.java");
            Files.writeString(sourceFile, "public class User {}");
            Files.writeString(srcTestJava.resolve("UserIntegrationTest.java"), ""); // Contains 'User'
            Files.writeString(srcTestJava.resolve("UserValidationTest.java"), ""); // Contains 'User'

            // When
            String result = testDiscoveryTool.findTestClasses(sourceFile.toString(), tempDir.toString());

            // Then
            assertTrue(result.contains("UserIntegrationTest.java") || result.contains("UserValidationTest.java"));
        }
    }

    @Nested
    @DisplayName("getExpectedTestPath Tests")
    class GetExpectedTestPathTests {

        @Test
        @DisplayName("Should convert src/main/java to src/test/java")
        void shouldConvertMainToTest() {
            // Given
            String sourcePath = "/project/src/main/java/com/example/UserService.java";

            // When
            String result = testDiscoveryTool.getExpectedTestPath(sourcePath);

            // Then
            assertTrue(result.contains("/src/test/java/"));
            assertFalse(result.contains("/src/main/java/"));
        }

        @Test
        @DisplayName("Should add Test suffix to class name")
        void shouldAddTestSuffix() {
            // Given
            String sourcePath = "/project/src/main/java/com/example/UserService.java";

            // When
            String result = testDiscoveryTool.getExpectedTestPath(sourcePath);

            // Then
            assertTrue(result.contains("UserServiceTest.java"));
            assertFalse(result.contains("UserService.java ("));
        }

        @Test
        @DisplayName("Should indicate if test file exists")
        void shouldIndicateIfTestFileExists() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("ExistingService.java");
            Files.writeString(sourceFile, "public class ExistingService {}");
            Files.writeString(srcTestJava.resolve("ExistingServiceTest.java"), "public class ExistingServiceTest {}");

            // When
            String result = testDiscoveryTool.getExpectedTestPath(sourceFile.toString());

            // Then
            assertTrue(result.contains("(exists)"));
        }

        @Test
        @DisplayName("Should indicate if test file does not exist")
        void shouldIndicateIfTestFileNotExists() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(srcMainJava);

            Path sourceFile = srcMainJava.resolve("NewService.java");
            Files.writeString(sourceFile, "public class NewService {}");
            // Don't create test file

            // When
            String result = testDiscoveryTool.getExpectedTestPath(sourceFile.toString());

            // Then
            assertTrue(result.contains("(not exists)"));
        }

        @Test
        @DisplayName("Should normalize Windows path separators")
        void shouldNormalizeWindowsPathSeparators() {
            // Given
            String windowsPath = "C:\\project\\src\\main\\java\\com\\example\\Service.java";

            // When
            String result = testDiscoveryTool.getExpectedTestPath(windowsPath);

            // Then
            assertTrue(result.contains("/src/test/java/"));
            assertTrue(result.contains("ServiceTest.java"));
        }
    }

    @Nested
    @DisplayName("hasTestClass Tests")
    class HasTestClassTests {

        @Test
        @DisplayName("Should return true when test class exists with Test suffix")
        void shouldReturnTrueWhenTestExists() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("UserService.java");
            Files.writeString(sourceFile, "public class UserService {}");
            Files.writeString(srcTestJava.resolve("UserServiceTest.java"), "public class UserServiceTest {}");

            // When
            boolean result = testDiscoveryTool.hasTestClass(sourceFile.toString());

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true when test class exists with Tests suffix")
        void shouldReturnTrueWhenTestsExists() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("OrderService.java");
            Files.writeString(sourceFile, "public class OrderService {}");
            Files.writeString(srcTestJava.resolve("OrderServiceTests.java"), "public class OrderServiceTests {}");

            // When
            boolean result = testDiscoveryTool.hasTestClass(sourceFile.toString());

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true when test class exists with TestCase suffix")
        void shouldReturnTrueWhenTestCaseExists() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("Calculator.java");
            Files.writeString(sourceFile, "public class Calculator {}");
            Files.writeString(srcTestJava.resolve("CalculatorTestCase.java"), "public class CalculatorTestCase {}");

            // When
            boolean result = testDiscoveryTool.hasTestClass(sourceFile.toString());

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when no test class exists")
        void shouldReturnFalseWhenNoTestExists() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("NoTestService.java");
            Files.writeString(sourceFile, "public class NoTestService {}");
            // Don't create any test file

            // When
            boolean result = testDiscoveryTool.hasTestClass(sourceFile.toString());

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when test directory does not exist")
        void shouldReturnFalseWhenTestDirNotExists() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(srcMainJava);
            // Don't create src/test/java

            Path sourceFile = srcMainJava.resolve("Service.java");
            Files.writeString(sourceFile, "public class Service {}");

            // When
            boolean result = testDiscoveryTool.hasTestClass(sourceFile.toString());

            // Then
            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("Tool Annotation Tests")
    class ToolAnnotationTests {

        @Test
        @DisplayName("Should implement AgentTool interface")
        void shouldImplementAgentTool() {
            assertTrue(testDiscoveryTool instanceof AgentTool);
        }

        @Test
        @DisplayName("findTestClasses should have @Tool annotation")
        void findTestClasses_shouldHaveToolAnnotation() throws NoSuchMethodException {
            var method = TestDiscoveryTool.class.getMethod("findTestClasses", String.class, String.class);
            assertTrue(method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
        }

        @Test
        @DisplayName("getExpectedTestPath should have @Tool annotation")
        void getExpectedTestPath_shouldHaveToolAnnotation() throws NoSuchMethodException {
            var method = TestDiscoveryTool.class.getMethod("getExpectedTestPath", String.class);
            assertTrue(method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
        }

        @Test
        @DisplayName("hasTestClass should have @Tool annotation")
        void hasTestClass_shouldHaveToolAnnotation() throws NoSuchMethodException {
            var method = TestDiscoveryTool.class.getMethod("hasTestClass", String.class);
            assertTrue(method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle class names with numbers")
        void shouldHandleClassNamesWithNumbers() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("Base64Encoder.java");
            Files.writeString(sourceFile, "public class Base64Encoder {}");
            Files.writeString(srcTestJava.resolve("Base64EncoderTest.java"), "");

            // When
            String result = testDiscoveryTool.findTestClasses(sourceFile.toString(), tempDir.toString());

            // Then
            assertTrue(result.contains("Base64EncoderTest.java"));
        }

        @Test
        @DisplayName("Should handle deeply nested packages")
        void shouldHandleDeeplyNestedPackages() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example/deep/nested/package1");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example/deep/nested/package1");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Path sourceFile = srcMainJava.resolve("DeepService.java");
            Files.writeString(sourceFile, "public class DeepService {}");
            Files.writeString(srcTestJava.resolve("DeepServiceTest.java"), "");

            // When
            String result = testDiscoveryTool.findTestClasses(sourceFile.toString(), tempDir.toString());

            // Then
            assertTrue(result.contains("DeepServiceTest.java"));
        }
    }
}
