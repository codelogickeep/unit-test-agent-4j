package com.codelogickeep.agent.ut.tools;

import org.junit.jupiter.api.*;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MavenExecutorTool 单元测试
 * 只测试可以单元测试的部分（不依赖真实 Maven 环境）
 */
@DisplayName("MavenExecutorTool Tests")
class MavenExecutorToolTest {

    private MavenExecutorTool executor;

    @BeforeEach
    void setUp() {
        executor = new MavenExecutorTool();
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
            executor.setProjectRoot("/initial/path");
            Path originalPath = executor.getProjectRoot();
            executor.setProjectRoot(null);

            assertEquals(originalPath, executor.getProjectRoot());
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
}
