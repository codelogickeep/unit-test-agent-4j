package com.codelogickeep.agent.ut.tools;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MavenExecutorTool 单元测试
 * 重点测试 CompileGuard 集成和基本功能
 */
@DisplayName("MavenExecutorTool Tests")
class MavenExecutorToolTest {

    private MavenExecutorTool executor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        executor = new MavenExecutorTool();
        executor.setProjectRoot(tempDir.toString());
        CompileGuard.getInstance().setEnabled(true);
        CompileGuard.getInstance().clearAllStatus();
    }

    @AfterEach
    void tearDown() {
        CompileGuard.getInstance().clearAllStatus();
    }

    @Nested
    @DisplayName("Project Root Configuration")
    class ProjectRootConfiguration {

        @Test
        @DisplayName("setProjectRoot should update project root path")
        void setProjectRoot_shouldUpdatePath() {
            String newPath = "/custom/path";
            executor.setProjectRoot(newPath);
            
            Path result = executor.getProjectRoot();
            
            assertTrue(result.toString().contains("custom"));
        }

        @Test
        @DisplayName("setProjectRoot with null should keep current path")
        void setProjectRoot_withNull_shouldKeepCurrentPath() {
            Path originalPath = executor.getProjectRoot();
            executor.setProjectRoot(null);
            
            assertEquals(originalPath, executor.getProjectRoot());
        }
    }

    @Nested
    @DisplayName("CompileGuard Integration - compileProject")
    class CompileGuardIntegrationCompile {

        @Test
        @DisplayName("compileProject should be blocked when file needs syntax check")
        void compileProject_shouldBeBlocked_whenFileNeedsSyntaxCheck() throws IOException, InterruptedException {
            // Mark a file as modified (needs syntax check)
            CompileGuard.getInstance().markFileModified("/path/to/Test.java");
            
            MavenExecutorTool.ExecutionResult result = executor.compileProject();
            
            assertEquals(-1, result.exitCode());
            assertTrue(result.stdErr().contains("COMPILE_BLOCKED"));
        }

        @Test
        @DisplayName("compileProject should be blocked when syntax check failed")
        void compileProject_shouldBeBlocked_whenSyntaxCheckFailed() throws IOException, InterruptedException {
            CompileGuard.getInstance().markSyntaxFailed("/path/to/Test.java", "Missing semicolon");
            
            MavenExecutorTool.ExecutionResult result = executor.compileProject();
            
            assertEquals(-1, result.exitCode());
            assertTrue(result.stdErr().contains("COMPILE_BLOCKED"));
            assertTrue(result.stdErr().contains("Missing semicolon"));
        }

        @Test
        @DisplayName("compileProject should proceed when all files passed syntax check")
        void compileProject_shouldProceed_whenAllFilesPassed() throws IOException, InterruptedException {
            // Mark file as modified then passed
            CompileGuard.getInstance().markFileModified("/path/to/Test.java");
            CompileGuard.getInstance().markSyntaxPassed("/path/to/Test.java");
            
            // Since this is a temp dir without pom.xml, Maven will fail
            // But the important thing is it's not blocked by CompileGuard
            MavenExecutorTool.ExecutionResult result = executor.compileProject();
            
            // If result is -1 and stdErr contains COMPILE_BLOCKED, test fails
            // Otherwise, Maven tried to run (even if it fails due to no pom.xml)
            if (result.exitCode() == -1) {
                assertFalse(result.stdErr().contains("COMPILE_BLOCKED"), 
                    "Should not be blocked by CompileGuard");
            }
        }

        @Test
        @DisplayName("compileProject should proceed when guard is disabled")
        void compileProject_shouldProceed_whenGuardDisabled() throws IOException, InterruptedException {
            CompileGuard.getInstance().setEnabled(false);
            CompileGuard.getInstance().markFileModified("/path/to/Test.java");
            
            MavenExecutorTool.ExecutionResult result = executor.compileProject();
            
            // Should not be blocked, Maven may fail for other reasons
            if (result.exitCode() == -1) {
                assertFalse(result.stdErr().contains("COMPILE_BLOCKED"));
            }
        }
    }

    @Nested
    @DisplayName("CompileGuard Integration - executeTest")
    class CompileGuardIntegrationTest {

        @Test
        @DisplayName("executeTest should be blocked when file needs syntax check")
        void executeTest_shouldBeBlocked_whenFileNeedsSyntaxCheck() throws IOException, InterruptedException {
            CompileGuard.getInstance().markFileModified("/path/to/Test.java");
            
            MavenExecutorTool.ExecutionResult result = executor.executeTest("com.example.Test");
            
            assertEquals(-1, result.exitCode());
            assertTrue(result.stdErr().contains("COMPILE_BLOCKED"));
        }

        @Test
        @DisplayName("executeTest should proceed when all files passed")
        void executeTest_shouldProceed_whenAllFilesPassed() throws IOException, InterruptedException {
            CompileGuard.getInstance().markFileModified("/path/to/Test.java");
            CompileGuard.getInstance().markSyntaxPassed("/path/to/Test.java");
            
            MavenExecutorTool.ExecutionResult result = executor.executeTest("com.example.Test");
            
            if (result.exitCode() == -1) {
                assertFalse(result.stdErr().contains("COMPILE_BLOCKED"));
            }
        }
    }

    @Nested
    @DisplayName("CompileGuard Integration - cleanAndTest")
    class CompileGuardIntegrationCleanAndTest {

        @Test
        @DisplayName("cleanAndTest should be blocked when file needs syntax check")
        void cleanAndTest_shouldBeBlocked_whenFileNeedsSyntaxCheck() throws IOException, InterruptedException {
            CompileGuard.getInstance().markFileModified("/path/to/Test.java");
            
            MavenExecutorTool.ExecutionResult result = executor.cleanAndTest();
            
            assertEquals(-1, result.exitCode());
            assertTrue(result.stdErr().contains("COMPILE_BLOCKED"));
        }

        @Test
        @DisplayName("cleanAndTest should proceed when guard is disabled")
        void cleanAndTest_shouldProceed_whenGuardDisabled() throws IOException, InterruptedException {
            CompileGuard.getInstance().setEnabled(false);
            CompileGuard.getInstance().markSyntaxFailed("/path/to/Test.java", "Error");
            
            MavenExecutorTool.ExecutionResult result = executor.cleanAndTest();
            
            if (result.exitCode() == -1) {
                assertFalse(result.stdErr().contains("COMPILE_BLOCKED"));
            }
        }
    }

    @Nested
    @DisplayName("ExecutionResult Record")
    class ExecutionResultTest {

        @Test
        @DisplayName("ExecutionResult should store all fields correctly")
        void executionResult_shouldStoreAllFields() {
            MavenExecutorTool.ExecutionResult result = new MavenExecutorTool.ExecutionResult(
                0, "stdout content", "stderr content"
            );
            
            assertEquals(0, result.exitCode());
            assertEquals("stdout content", result.stdOut());
            assertEquals("stderr content", result.stdErr());
        }

        @Test
        @DisplayName("ExecutionResult should handle empty strings")
        void executionResult_shouldHandleEmptyStrings() {
            MavenExecutorTool.ExecutionResult result = new MavenExecutorTool.ExecutionResult(
                1, "", ""
            );
            
            assertEquals(1, result.exitCode());
            assertEquals("", result.stdOut());
            assertEquals("", result.stdErr());
        }
    }

    @Nested
    @DisplayName("Multiple Files Scenario")
    class MultipleFilesScenario {

        @Test
        @DisplayName("should block when any file fails syntax check")
        void shouldBlock_whenAnyFileFails() throws IOException, InterruptedException {
            CompileGuard.getInstance().markFileModified("/path/to/File1.java");
            CompileGuard.getInstance().markFileModified("/path/to/File2.java");
            CompileGuard.getInstance().markFileModified("/path/to/File3.java");
            
            // Only pass two files
            CompileGuard.getInstance().markSyntaxPassed("/path/to/File1.java");
            CompileGuard.getInstance().markSyntaxPassed("/path/to/File2.java");
            // File3 still pending
            
            MavenExecutorTool.ExecutionResult result = executor.compileProject();
            
            assertEquals(-1, result.exitCode());
            assertTrue(result.stdErr().contains("COMPILE_BLOCKED"));
            assertTrue(result.stdErr().contains("File3.java"));
        }

        @Test
        @DisplayName("should proceed when all files pass syntax check")
        void shouldProceed_whenAllFilesPass() throws IOException, InterruptedException {
            CompileGuard.getInstance().markFileModified("/path/to/File1.java");
            CompileGuard.getInstance().markFileModified("/path/to/File2.java");
            
            CompileGuard.getInstance().markSyntaxPassed("/path/to/File1.java");
            CompileGuard.getInstance().markSyntaxPassed("/path/to/File2.java");
            
            MavenExecutorTool.ExecutionResult result = executor.compileProject();
            
            // Should not be blocked by CompileGuard
            if (result.exitCode() == -1) {
                assertFalse(result.stdErr().contains("COMPILE_BLOCKED"));
            }
        }
    }

    @Nested
    @DisplayName("Error Message Quality")
    class ErrorMessageQuality {

        @Test
        @DisplayName("blocked message should contain clear instructions")
        void blockedMessage_shouldContainClearInstructions() throws IOException, InterruptedException {
            CompileGuard.getInstance().markFileModified("/path/to/Test.java");
            
            MavenExecutorTool.ExecutionResult result = executor.compileProject();
            
            String errorMsg = result.stdErr();
            assertTrue(errorMsg.contains("checkSyntax"), "Should mention checkSyntax");
            assertTrue(errorMsg.contains("REQUIRED ACTION") || errorMsg.contains("ACTION"), 
                "Should have action guidance");
        }

        @Test
        @DisplayName("blocked message should list affected files")
        void blockedMessage_shouldListAffectedFiles() throws IOException, InterruptedException {
            CompileGuard.getInstance().markSyntaxFailed("/path/to/FailedFile.java", "Syntax error");
            
            MavenExecutorTool.ExecutionResult result = executor.compileProject();
            
            assertTrue(result.stdErr().contains("FailedFile.java"));
        }
    }
}
