package com.codelogickeep.agent.ut.tools;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CompileGuard 单元测试
 * 测试编译守卫机制的核心功能
 */
@DisplayName("CompileGuard Tests")
class CompileGuardTest {

    private CompileGuard guard;

    @BeforeEach
    void setUp() {
        guard = CompileGuard.getInstance();
        guard.setEnabled(true);
        guard.clearAllStatus();
    }

    @AfterEach
    void tearDown() {
        guard.clearAllStatus();
    }

    @Nested
    @DisplayName("Basic Operations")
    class BasicOperations {

        @Test
        @DisplayName("getInstance should return singleton instance")
        void getInstance_shouldReturnSingleton() {
            CompileGuard instance1 = CompileGuard.getInstance();
            CompileGuard instance2 = CompileGuard.getInstance();
            assertSame(instance1, instance2);
        }

        @Test
        @DisplayName("setEnabled should toggle guard state")
        void setEnabled_shouldToggleState() {
            guard.setEnabled(false);
            assertFalse(guard.isEnabled());
            
            guard.setEnabled(true);
            assertTrue(guard.isEnabled());
        }

        @Test
        @DisplayName("clearAllStatus should remove all tracked files")
        void clearAllStatus_shouldRemoveAllFiles() {
            guard.markFileModified("/path/to/File1.java");
            guard.markFileModified("/path/to/File2.java");
            
            guard.clearAllStatus();
            
            // After clearing, canCompile should return OK
            CompileGuard.CompileCheckResult result = guard.canCompile();
            assertTrue(result.canCompile());
        }

        @Test
        @DisplayName("clearStatus should remove specific file")
        void clearStatus_shouldRemoveSpecificFile() {
            guard.markFileModified("/path/to/File1.java");
            guard.markFileModified("/path/to/File2.java");
            
            guard.clearStatus("/path/to/File1.java");
            
            // File2 still needs check
            CompileGuard.CompileCheckResult result = guard.canCompile();
            assertFalse(result.canCompile());
            assertTrue(result.blockReason().contains("File2.java"));
        }
    }

    @Nested
    @DisplayName("File Status Tracking")
    class FileStatusTracking {

        @Test
        @DisplayName("markFileModified should block compilation")
        void markFileModified_shouldBlockCompilation() {
            guard.markFileModified("/path/to/Test.java");
            
            CompileGuard.CompileCheckResult result = guard.canCompile();
            
            assertFalse(result.canCompile());
            assertNotNull(result.blockReason());
            assertTrue(result.blockReason().contains("COMPILE_BLOCKED"));
        }

        @Test
        @DisplayName("markSyntaxPassed should allow compilation")
        void markSyntaxPassed_shouldAllowCompilation() {
            guard.markFileModified("/path/to/Test.java");
            guard.markSyntaxPassed("/path/to/Test.java");
            
            CompileGuard.CompileCheckResult result = guard.canCompile();
            
            assertTrue(result.canCompile());
            assertNull(result.blockReason());
        }

        @Test
        @DisplayName("markSyntaxFailed should block compilation with error message")
        void markSyntaxFailed_shouldBlockWithErrorMessage() {
            guard.markFileModified("/path/to/Test.java");
            guard.markSyntaxFailed("/path/to/Test.java", "Missing semicolon at line 10");
            
            CompileGuard.CompileCheckResult result = guard.canCompile();
            
            assertFalse(result.canCompile());
            assertTrue(result.blockReason().contains("Test.java"));
            assertTrue(result.blockReason().contains("Missing semicolon"));
        }

        @Test
        @DisplayName("multiple files should all be tracked")
        void multipleFiles_shouldAllBeTracked() {
            guard.markFileModified("/path/to/File1.java");
            guard.markFileModified("/path/to/File2.java");
            guard.markFileModified("/path/to/File3.java");
            
            // Only pass File1
            guard.markSyntaxPassed("/path/to/File1.java");
            
            CompileGuard.CompileCheckResult result = guard.canCompile();
            
            assertFalse(result.canCompile());
            assertTrue(result.blockReason().contains("2 file(s)"));
        }

        @Test
        @DisplayName("all files passed should allow compilation")
        void allFilesPassed_shouldAllowCompilation() {
            guard.markFileModified("/path/to/File1.java");
            guard.markFileModified("/path/to/File2.java");
            
            guard.markSyntaxPassed("/path/to/File1.java");
            guard.markSyntaxPassed("/path/to/File2.java");
            
            CompileGuard.CompileCheckResult result = guard.canCompile();
            
            assertTrue(result.canCompile());
        }
    }

    @Nested
    @DisplayName("Guard Disabled Behavior")
    class GuardDisabledBehavior {

        @Test
        @DisplayName("when disabled, markFileModified should be no-op")
        void whenDisabled_markFileModified_shouldBeNoOp() {
            guard.setEnabled(false);
            guard.markFileModified("/path/to/Test.java");
            
            CompileGuard.CompileCheckResult result = guard.canCompile();
            
            assertTrue(result.canCompile());
        }

        @Test
        @DisplayName("when disabled, canCompile should always return OK")
        void whenDisabled_canCompile_shouldAlwaysReturnOk() {
            guard.markFileModified("/path/to/Test.java");
            guard.setEnabled(false);
            
            CompileGuard.CompileCheckResult result = guard.canCompile();
            
            assertTrue(result.canCompile());
        }
    }

    @Nested
    @DisplayName("Status Summary")
    class StatusSummary {

        @Test
        @DisplayName("getStatusSummary should show disabled state")
        void getStatusSummary_shouldShowDisabledState() {
            guard.setEnabled(false);
            
            String summary = guard.getStatusSummary();
            
            assertTrue(summary.contains("DISABLED"));
        }

        @Test
        @DisplayName("getStatusSummary should show passed and pending counts")
        void getStatusSummary_shouldShowCounts() {
            guard.markFileModified("/path/to/File1.java");
            guard.markFileModified("/path/to/File2.java");
            guard.markSyntaxPassed("/path/to/File1.java");
            
            String summary = guard.getStatusSummary();
            
            assertTrue(summary.contains("1 passed"));
            assertTrue(summary.contains("1 pending"));
        }

        @Test
        @DisplayName("getStatusSummary should list pending files")
        void getStatusSummary_shouldListPendingFiles() {
            guard.markFileModified("/path/to/PendingFile.java");
            
            String summary = guard.getStatusSummary();
            
            assertTrue(summary.contains("PendingFile.java"));
        }
    }

    @Nested
    @DisplayName("Path Normalization")
    class PathNormalization {

        @Test
        @DisplayName("should normalize paths consistently")
        void shouldNormalizePathsConsistently() {
            // Use relative and absolute paths for the same file
            guard.markFileModified("src/main/java/Test.java");
            guard.markSyntaxPassed("./src/main/java/Test.java");
            
            CompileGuard.CompileCheckResult result = guard.canCompile();
            
            // Should recognize as the same file
            assertTrue(result.canCompile());
        }
    }

    @Nested
    @DisplayName("CompileCheckResult")
    class CompileCheckResultTest {

        @Test
        @DisplayName("ok() should create passing result")
        void ok_shouldCreatePassingResult() {
            CompileGuard.CompileCheckResult result = CompileGuard.CompileCheckResult.ok();
            
            assertTrue(result.canCompile());
            assertNull(result.blockReason());
        }

        @Test
        @DisplayName("blocked() should create blocking result with reason")
        void blocked_shouldCreateBlockingResultWithReason() {
            String reason = "Test reason";
            CompileGuard.CompileCheckResult result = CompileGuard.CompileCheckResult.blocked(reason);
            
            assertFalse(result.canCompile());
            assertEquals(reason, result.blockReason());
        }
    }
}
