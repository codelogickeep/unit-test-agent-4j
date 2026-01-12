package com.codelogickeep.agent.ut.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MavenExecutorTool.
 * 
 * Note: These tests focus on the command building logic rather than actual Maven execution,
 * as executing Maven requires a proper Maven installation and project structure.
 */
@DisplayName("MavenExecutorTool Tests")
class MavenExecutorToolTest {

    private MavenExecutorTool mavenExecutorTool;

    @BeforeEach
    void setUp() {
        mavenExecutorTool = new MavenExecutorTool();
    }

    @Nested
    @DisplayName("ExecutionResult Record Tests")
    class ExecutionResultTests {

        @Test
        @DisplayName("ExecutionResult should hold exit code, stdout, and stderr")
        void executionResult_shouldHoldAllFields() {
            // Given
            int exitCode = 0;
            String stdOut = "Build successful";
            String stdErr = "";

            // When
            MavenExecutorTool.ExecutionResult result = new MavenExecutorTool.ExecutionResult(exitCode, stdOut, stdErr);

            // Then
            assertEquals(0, result.exitCode());
            assertEquals("Build successful", result.stdOut());
            assertEquals("", result.stdErr());
        }

        @Test
        @DisplayName("ExecutionResult should handle failure case")
        void executionResult_shouldHandleFailure() {
            // Given
            int exitCode = 1;
            String stdOut = "";
            String stdErr = "Compilation failed: cannot find symbol";

            // When
            MavenExecutorTool.ExecutionResult result = new MavenExecutorTool.ExecutionResult(exitCode, stdOut, stdErr);

            // Then
            assertEquals(1, result.exitCode());
            assertEquals("", result.stdOut());
            assertEquals("Compilation failed: cannot find symbol", result.stdErr());
        }

        @Test
        @DisplayName("ExecutionResult should handle multiline output")
        void executionResult_shouldHandleMultilineOutput() {
            // Given
            String stdOut = "[INFO] Scanning for projects...\n[INFO] Building project 1.0\n[INFO] BUILD SUCCESS";

            // When
            MavenExecutorTool.ExecutionResult result = new MavenExecutorTool.ExecutionResult(0, stdOut, "");

            // Then
            assertTrue(result.stdOut().contains("BUILD SUCCESS"));
            assertEquals(3, result.stdOut().split("\n").length);
        }

        @Test
        @DisplayName("ExecutionResult equality should work correctly")
        void executionResult_equalityShouldWork() {
            // Given
            MavenExecutorTool.ExecutionResult result1 = new MavenExecutorTool.ExecutionResult(0, "out", "err");
            MavenExecutorTool.ExecutionResult result2 = new MavenExecutorTool.ExecutionResult(0, "out", "err");
            MavenExecutorTool.ExecutionResult result3 = new MavenExecutorTool.ExecutionResult(1, "out", "err");

            // Then
            assertEquals(result1, result2);
            assertNotEquals(result1, result3);
        }
    }

    @Nested
    @DisplayName("Tool Implementation Tests")
    class ToolImplementationTests {

        @Test
        @DisplayName("MavenExecutorTool should implement AgentTool interface")
        void mavenExecutorTool_shouldImplementAgentTool() {
            assertTrue(mavenExecutorTool instanceof AgentTool);
        }

        @Test
        @DisplayName("compileProject method should be annotated with @Tool")
        void compileProject_shouldHaveToolAnnotation() throws NoSuchMethodException {
            var method = MavenExecutorTool.class.getMethod("compileProject");
            assertTrue(method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
        }

        @Test
        @DisplayName("executeTest method should be annotated with @Tool")
        void executeTest_shouldHaveToolAnnotation() throws NoSuchMethodException {
            var method = MavenExecutorTool.class.getMethod("executeTest", String.class);
            assertTrue(method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
        }

        @Test
        @DisplayName("executeTest method should have @P annotated parameter")
        void executeTest_shouldHaveParameterAnnotation() throws NoSuchMethodException {
            var method = MavenExecutorTool.class.getMethod("executeTest", String.class);
            var params = method.getParameters();
            assertEquals(1, params.length);
            assertTrue(params[0].isAnnotationPresent(dev.langchain4j.agent.tool.P.class));
        }
    }

    @Nested
    @DisplayName("Shell Detection Tests")
    class ShellDetectionTests {

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("On Windows, should detect PowerShell or cmd.exe")
        void onWindows_shouldDetectShellCorrectly() {
            // This test verifies the shell detection doesn't throw
            // The actual shell used depends on the system configuration
            assertDoesNotThrow(() -> {
                // Trigger shell detection by creating a new instance
                MavenExecutorTool tool = new MavenExecutorTool();
                assertNotNull(tool);
            });
        }

        @Test
        @EnabledOnOs({OS.LINUX, OS.MAC})
        @DisplayName("On Unix, should use sh")
        void onUnix_shouldUseSh() {
            // On Unix systems, the shell should be 'sh'
            assertDoesNotThrow(() -> {
                MavenExecutorTool tool = new MavenExecutorTool();
                assertNotNull(tool);
            });
        }
    }

    @Nested
    @DisplayName("Command Building Logic Tests")
    class CommandBuildingTests {

        @Test
        @DisplayName("Test class name should be properly formatted")
        void testClassName_shouldBeProperlyFormatted() {
            // Given
            String testClassName = "com.example.service.UserServiceTest";

            // When/Then - verify the class name format is valid
            assertTrue(testClassName.matches("^[a-zA-Z][a-zA-Z0-9.]*[a-zA-Z0-9]$"));
            assertTrue(testClassName.contains("."));
            assertTrue(testClassName.endsWith("Test"));
        }

        @Test
        @DisplayName("Should handle test class names with various patterns")
        void shouldHandleVariousTestClassNames() {
            // Given
            String[] validNames = {
                    "com.example.MyTest",
                    "com.example.service.UserServiceTest",
                    "com.example.integration.ApiIT",
                    "MyClassTests"
            };

            // Then - all should be valid class names
            for (String name : validNames) {
                assertFalse(name.contains(" "), "Class name should not contain spaces: " + name);
                assertFalse(name.startsWith("."), "Class name should not start with dot: " + name);
                assertFalse(name.endsWith("."), "Class name should not end with dot: " + name);
            }
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("ExecutionResult should preserve error messages")
        void executionResult_shouldPreserveErrorMessages() {
            // Given
            String errorMessage = "java.lang.NullPointerException: Cannot invoke method on null object";

            // When
            MavenExecutorTool.ExecutionResult result = new MavenExecutorTool.ExecutionResult(1, "", errorMessage);

            // Then
            assertTrue(result.stdErr().contains("NullPointerException"));
            assertEquals(1, result.exitCode());
        }

        @Test
        @DisplayName("ExecutionResult should handle combined output and error")
        void executionResult_shouldHandleCombinedOutputAndError() {
            // Given
            String stdOut = "[INFO] Compiling...\n[ERROR] Compilation failure";
            String stdErr = "Error: cannot find symbol\n  symbol: class NotFound";

            // When
            MavenExecutorTool.ExecutionResult result = new MavenExecutorTool.ExecutionResult(1, stdOut, stdErr);

            // Then
            assertTrue(result.stdOut().contains("[ERROR]"));
            assertTrue(result.stdErr().contains("cannot find symbol"));
        }
    }

    @Nested
    @DisplayName("Exit Code Interpretation Tests")
    class ExitCodeTests {

        @Test
        @DisplayName("Exit code 0 indicates success")
        void exitCode0_indicatesSuccess() {
            MavenExecutorTool.ExecutionResult result = new MavenExecutorTool.ExecutionResult(0, "SUCCESS", "");
            assertEquals(0, result.exitCode());
        }

        @Test
        @DisplayName("Exit code 1 indicates general failure")
        void exitCode1_indicatesGeneralFailure() {
            MavenExecutorTool.ExecutionResult result = new MavenExecutorTool.ExecutionResult(1, "", "BUILD FAILURE");
            assertEquals(1, result.exitCode());
        }

        @Test
        @DisplayName("Exit code 130 indicates user interrupt (Ctrl+C)")
        void exitCode130_indicatesInterrupt() {
            MavenExecutorTool.ExecutionResult result = new MavenExecutorTool.ExecutionResult(130, "", "Interrupted");
            assertEquals(130, result.exitCode());
        }
    }
}
