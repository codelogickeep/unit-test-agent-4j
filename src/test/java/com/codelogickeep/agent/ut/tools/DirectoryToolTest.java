package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.exception.AgentToolException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DirectoryTool.
 * Tests directory operations: list, exists, create.
 */
class DirectoryToolTest {

    private DirectoryTool tool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        tool = new DirectoryTool();
    }

    // ==================== listFiles Tests ====================

    @Test
    @DisplayName("listFiles should return empty list for empty directory")
    void listFiles_shouldReturnEmptyListForEmptyDirectory() throws IOException {
        List<String> result = tool.listFiles(tempDir.toString());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("listFiles should list files in directory")
    void listFiles_shouldListFilesInDirectory() throws IOException {
        Files.writeString(tempDir.resolve("file1.txt"), "content1");
        Files.writeString(tempDir.resolve("file2.java"), "content2");

        List<String> result = tool.listFiles(tempDir.toString());

        assertEquals(2, result.size());
        assertTrue(result.contains("file1.txt"));
        assertTrue(result.contains("file2.java"));
    }

    @Test
    @DisplayName("listFiles should suffix directories with slash")
    void listFiles_shouldSuffixDirectoriesWithSlash() throws IOException {
        Files.createDirectory(tempDir.resolve("subdir"));
        Files.writeString(tempDir.resolve("file.txt"), "content");

        List<String> result = tool.listFiles(tempDir.toString());

        assertEquals(2, result.size());
        assertTrue(result.contains("subdir/"));
        assertTrue(result.contains("file.txt"));
    }

    @Test
    @DisplayName("listFiles should throw AgentToolException for non-existing path")
    void listFiles_shouldThrowForNonExistingPath() {
        AgentToolException exception = assertThrows(AgentToolException.class, 
            () -> tool.listFiles(tempDir.resolve("nonexistent").toString()));
        assertTrue(exception.getMessage().contains("not found"));
    }

    @Test
    @DisplayName("listFiles should throw IOException for file path")
    void listFiles_shouldThrowForFilePath() throws IOException {
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "content");

        AgentToolException exception = assertThrows(AgentToolException.class, 
            () -> tool.listFiles(file.toString()));
        assertTrue(exception.getMessage().contains("not a directory"));
    }

    @Test
    @DisplayName("listFiles should handle mixed content")
    void listFiles_shouldHandleMixedContent() throws IOException {
        Files.createDirectory(tempDir.resolve("dir1"));
        Files.createDirectory(tempDir.resolve("dir2"));
        Files.writeString(tempDir.resolve("file1.txt"), "content");
        Files.writeString(tempDir.resolve("file2.java"), "content");

        List<String> result = tool.listFiles(tempDir.toString());

        assertEquals(4, result.size());
        long dirCount = result.stream().filter(s -> s.endsWith("/")).count();
        long fileCount = result.stream().filter(s -> !s.endsWith("/")).count();
        assertEquals(2, dirCount);
        assertEquals(2, fileCount);
    }

    // ==================== directoryExists Tests ====================

    @Test
    @DisplayName("directoryExists should return true for existing directory")
    void directoryExists_shouldReturnTrueForExistingDirectory() {
        assertTrue(tool.directoryExists(tempDir.toString()));
    }

    @Test
    @DisplayName("directoryExists should return false for non-existing path")
    void directoryExists_shouldReturnFalseForNonExistingPath() {
        assertFalse(tool.directoryExists(tempDir.resolve("nonexistent").toString()));
    }

    @Test
    @DisplayName("directoryExists should return false for file")
    void directoryExists_shouldReturnFalseForFile() throws IOException {
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "content");

        assertFalse(tool.directoryExists(file.toString()));
    }

    @Test
    @DisplayName("directoryExists should handle nested directories")
    void directoryExists_shouldHandleNestedDirectories() throws IOException {
        Path nested = tempDir.resolve("a/b/c");
        Files.createDirectories(nested);

        assertTrue(tool.directoryExists(nested.toString()));
        assertTrue(tool.directoryExists(tempDir.resolve("a/b").toString()));
        assertTrue(tool.directoryExists(tempDir.resolve("a").toString()));
    }

    // ==================== createDirectory Tests ====================

    @Test
    @DisplayName("createDirectory should create single directory")
    void createDirectory_shouldCreateSingleDirectory() throws IOException {
        Path newDir = tempDir.resolve("newdir");

        tool.createDirectory(newDir.toString());

        assertTrue(Files.exists(newDir));
        assertTrue(Files.isDirectory(newDir));
    }

    @Test
    @DisplayName("createDirectory should create nested directories")
    void createDirectory_shouldCreateNestedDirectories() throws IOException {
        Path nestedDir = tempDir.resolve("a/b/c/d");

        tool.createDirectory(nestedDir.toString());

        assertTrue(Files.exists(nestedDir));
        assertTrue(Files.isDirectory(nestedDir));
        assertTrue(Files.isDirectory(tempDir.resolve("a/b/c")));
        assertTrue(Files.isDirectory(tempDir.resolve("a/b")));
        assertTrue(Files.isDirectory(tempDir.resolve("a")));
    }

    @Test
    @DisplayName("createDirectory should not throw if directory already exists")
    void createDirectory_shouldNotThrowIfDirectoryAlreadyExists() throws IOException {
        Path existingDir = tempDir.resolve("existing");
        Files.createDirectory(existingDir);

        // Should not throw
        assertDoesNotThrow(() -> tool.createDirectory(existingDir.toString()));
        assertTrue(Files.exists(existingDir));
    }

    @Test
    @DisplayName("createDirectory should handle special characters in path")
    void createDirectory_shouldHandleSpecialCharactersInPath() throws IOException {
        // Note: Some special chars may not work on all OS
        Path specialDir = tempDir.resolve("dir-with_special.chars");

        tool.createDirectory(specialDir.toString());

        assertTrue(Files.exists(specialDir));
        assertTrue(Files.isDirectory(specialDir));
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("should handle empty directory name components")
    void shouldHandleRelativePaths() throws IOException {
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectory(subDir);
        Files.writeString(subDir.resolve("file.txt"), "content");

        List<String> result = tool.listFiles(subDir.toString());

        assertEquals(1, result.size());
        assertEquals("file.txt", result.get(0));
    }

    @Test
    @DisplayName("listFiles should handle files with special names")
    void listFiles_shouldHandleFilesWithSpecialNames() throws IOException {
        Files.writeString(tempDir.resolve("file with spaces.txt"), "content");
        Files.writeString(tempDir.resolve("file-with-dashes.txt"), "content");
        Files.writeString(tempDir.resolve("file.multiple.dots.txt"), "content");

        List<String> result = tool.listFiles(tempDir.toString());

        assertEquals(3, result.size());
        assertTrue(result.contains("file with spaces.txt"));
        assertTrue(result.contains("file-with-dashes.txt"));
        assertTrue(result.contains("file.multiple.dots.txt"));
    }
}
