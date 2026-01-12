package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.exception.AgentToolException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FileSystemTool.
 * Tests path safety, file operations, and error handling.
 */
class FileSystemToolTest {

    private FileSystemTool tool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        tool = new FileSystemTool();
        tool.setProjectRoot(tempDir.toString());
    }

    // ==================== Path Safety Tests ====================

    @Test
    @DisplayName("resolveSafePath should reject null path")
    void resolveSafePath_shouldRejectNullPath() {
        assertFalse(tool.fileExists(null));
    }

    @Test
    @DisplayName("resolveSafePath should reject empty path")
    void resolveSafePath_shouldRejectEmptyPath() {
        assertFalse(tool.fileExists(""));
        assertFalse(tool.fileExists("   "));
    }

    @Test
    @DisplayName("resolveSafePath should reject 'null' string")
    void resolveSafePath_shouldRejectNullString() {
        assertFalse(tool.fileExists("null"));
    }

    @Test
    @DisplayName("resolveSafePath should reject path traversal attempts")
    void resolveSafePath_shouldRejectPathTraversal() throws IOException {
        // Create a file outside the temp directory
        Path outsideFile = tempDir.getParent().resolve("outside.txt");
        
        // Attempt to access using path traversal
        assertThrows(AgentToolException.class, () -> tool.readFile("../outside.txt"));
    }

    @Test
    @DisplayName("resolveSafePath should accept relative paths within project root")
    void resolveSafePath_shouldAcceptRelativePaths() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "content");

        assertTrue(tool.fileExists("test.txt"));
    }

    @Test
    @DisplayName("resolveSafePath should accept absolute paths within project root")
    void resolveSafePath_shouldAcceptAbsolutePathsWithinRoot() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "content");

        assertTrue(tool.fileExists(testFile.toString()));
    }

    @Test
    @DisplayName("resolveSafePath should reject absolute paths outside project root")
    void resolveSafePath_shouldRejectAbsolutePathsOutsideRoot() {
        Path outsidePath = tempDir.getParent().resolve("outside.txt");
        assertFalse(tool.fileExists(outsidePath.toString()));
    }

    // ==================== fileExists Tests ====================

    @Test
    @DisplayName("fileExists should return true for existing file")
    void fileExists_shouldReturnTrueForExistingFile() throws IOException {
        Path testFile = tempDir.resolve("existing.txt");
        Files.writeString(testFile, "content");

        assertTrue(tool.fileExists("existing.txt"));
    }

    @Test
    @DisplayName("fileExists should return false for non-existing file")
    void fileExists_shouldReturnFalseForNonExistingFile() {
        assertFalse(tool.fileExists("nonexistent.txt"));
    }

    @Test
    @DisplayName("fileExists should return false for directory")
    void fileExists_shouldReturnFalseForDirectory() throws IOException {
        Path dir = tempDir.resolve("subdir");
        Files.createDirectory(dir);

        assertFalse(tool.fileExists("subdir"));
    }

    // ==================== readFile Tests ====================

    @Test
    @DisplayName("readFile should return file content")
    void readFile_shouldReturnFileContent() throws IOException {
        String content = "Hello, World!\nLine 2";
        Path testFile = tempDir.resolve("readable.txt");
        Files.writeString(testFile, content);

        String result = tool.readFile("readable.txt");
        assertEquals(content, result);
    }

    @Test
    @DisplayName("readFile should throw AgentToolException for non-existing file")
    void readFile_shouldThrowForNonExistingFile() {
        assertThrows(AgentToolException.class, () -> tool.readFile("nonexistent.txt"));
    }

    @Test
    @DisplayName("readFile should handle UTF-8 content")
    void readFile_shouldHandleUtf8Content() throws IOException {
        String content = "你好，世界！\n日本語テスト\nÉmoji: 🎉";
        Path testFile = tempDir.resolve("utf8.txt");
        Files.writeString(testFile, content);

        String result = tool.readFile("utf8.txt");
        assertEquals(content, result);
    }

    @Test
    @DisplayName("readFile should handle empty file")
    void readFile_shouldHandleEmptyFile() throws IOException {
        Path testFile = tempDir.resolve("empty.txt");
        Files.writeString(testFile, "");

        String result = tool.readFile("empty.txt");
        assertEquals("", result);
    }

    // ==================== writeFile Tests ====================

    @Test
    @DisplayName("writeFile should create new file")
    void writeFile_shouldCreateNewFile() throws IOException {
        String content = "New file content";
        String result = tool.writeFile("newfile.txt", content);

        assertTrue(result.contains("SUCCESS"));
        assertEquals(content, Files.readString(tempDir.resolve("newfile.txt")));
    }

    @Test
    @DisplayName("writeFile should create parent directories")
    void writeFile_shouldCreateParentDirectories() throws IOException {
        String content = "Nested file";
        String result = tool.writeFile("nested/deep/file.txt", content);

        assertTrue(result.contains("SUCCESS"));
        assertTrue(Files.exists(tempDir.resolve("nested/deep/file.txt")));
        assertEquals(content, Files.readString(tempDir.resolve("nested/deep/file.txt")));
    }

    @Test
    @DisplayName("writeFile should overwrite existing file")
    void writeFile_shouldOverwriteExistingFile() throws IOException {
        Path testFile = tempDir.resolve("overwrite.txt");
        Files.writeString(testFile, "Old content");

        String newContent = "New content";
        String result = tool.writeFile("overwrite.txt", newContent);

        assertTrue(result.contains("SUCCESS"));
        assertEquals(newContent, Files.readString(testFile));
    }

    @Test
    @DisplayName("writeFile should handle UTF-8 content")
    void writeFile_shouldHandleUtf8Content() throws IOException {
        String content = "中文内容\n日本語\n🚀";
        String result = tool.writeFile("utf8write.txt", content);

        assertTrue(result.contains("SUCCESS"));
        assertEquals(content, Files.readString(tempDir.resolve("utf8write.txt")));
    }

    // ==================== searchReplace Tests ====================

    @Test
    @DisplayName("searchReplace should replace first occurrence")
    void searchReplace_shouldReplaceFirstOccurrence() throws IOException {
        Path testFile = tempDir.resolve("replace.txt");
        Files.writeString(testFile, "Hello World! Hello Again!");

        String result = tool.searchReplace("replace.txt", "Hello", "Hi");

        assertTrue(result.contains("SUCCESS"));
        assertEquals("Hi World! Hello Again!", Files.readString(testFile));
    }

    @Test
    @DisplayName("searchReplace should return error for non-existing file")
    void searchReplace_shouldReturnErrorForNonExistingFile() throws IOException {
        String result = tool.searchReplace("nonexistent.txt", "old", "new");
        assertTrue(result.contains("ERROR"));
        assertTrue(result.contains("not found") || result.contains("File not found"));
    }

    @Test
    @DisplayName("searchReplace should return error when oldString not found")
    void searchReplace_shouldReturnErrorWhenOldStringNotFound() throws IOException {
        Path testFile = tempDir.resolve("noreplace.txt");
        Files.writeString(testFile, "Hello World!");

        String result = tool.searchReplace("noreplace.txt", "Goodbye", "Hi");

        assertTrue(result.contains("ERROR"));
        assertTrue(result.contains("NOT FOUND"));
    }

    @Test
    @DisplayName("searchReplace should handle special regex characters")
    void searchReplace_shouldHandleSpecialRegexChars() throws IOException {
        Path testFile = tempDir.resolve("regex.txt");
        Files.writeString(testFile, "Price: $100.00");

        String result = tool.searchReplace("regex.txt", "$100.00", "$200.00");

        assertTrue(result.contains("SUCCESS"));
        assertEquals("Price: $200.00", Files.readString(testFile));
    }

    @Test
    @DisplayName("searchReplace should handle multiline replacement")
    void searchReplace_shouldHandleMultilineReplacement() throws IOException {
        Path testFile = tempDir.resolve("multiline.txt");
        String original = "Line 1\nLine 2\nLine 3";
        Files.writeString(testFile, original);

        String result = tool.searchReplace("multiline.txt", "Line 2", "Modified Line 2\nExtra Line");

        assertTrue(result.contains("SUCCESS"));
        assertEquals("Line 1\nModified Line 2\nExtra Line\nLine 3", Files.readString(testFile));
    }

    // ==================== writeFileFromLine Tests ====================

    @Test
    @DisplayName("writeFileFromLine should replace content from specified line")
    void writeFileFromLine_shouldReplaceFromSpecifiedLine() throws IOException {
        Path testFile = tempDir.resolve("fromline.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\nLine 4");

        String result = tool.writeFileFromLine("fromline.txt", "New content from line 3", 3);

        assertTrue(result.contains("SUCCESS"));
        String expected = "Line 1" + System.lineSeparator() + "Line 2" + System.lineSeparator() + "New content from line 3";
        assertEquals(expected, Files.readString(testFile));
    }

    @Test
    @DisplayName("writeFileFromLine should create file if not exists")
    void writeFileFromLine_shouldCreateFileIfNotExists() throws IOException {
        String result = tool.writeFileFromLine("newfile_fromline.txt", "Content", 1);

        assertTrue(result.contains("SUCCESS"));
        assertTrue(Files.exists(tempDir.resolve("newfile_fromline.txt")));
    }

    @Test
    @DisplayName("writeFileFromLine should handle line 1")
    void writeFileFromLine_shouldHandleLineOne() throws IOException {
        Path testFile = tempDir.resolve("line1.txt");
        Files.writeString(testFile, "Old content");

        String result = tool.writeFileFromLine("line1.txt", "New content", 1);

        assertTrue(result.contains("SUCCESS"));
        assertEquals("New content", Files.readString(testFile));
    }

    // ==================== setProjectRoot Tests ====================

    @Test
    @DisplayName("setProjectRoot should change the root directory")
    void setProjectRoot_shouldChangeRootDirectory() throws IOException {
        // Create a subdirectory and set it as new root
        Path subDir = tempDir.resolve("subroot");
        Files.createDirectory(subDir);
        Path subFile = subDir.resolve("subfile.txt");
        Files.writeString(subFile, "Sub content");

        FileSystemTool newTool = new FileSystemTool();
        newTool.setProjectRoot(subDir.toString());

        assertTrue(newTool.fileExists("subfile.txt"));
        // Original tempDir file should not be accessible via relative path
        Path origFile = tempDir.resolve("original.txt");
        Files.writeString(origFile, "Original");
        assertFalse(newTool.fileExists("original.txt"));
    }

    @Test
    @DisplayName("setProjectRoot should handle null gracefully")
    void setProjectRoot_shouldHandleNull() {
        // Should not throw, just keep existing root
        assertDoesNotThrow(() -> tool.setProjectRoot(null));
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("should handle files with special characters in name")
    void shouldHandleFilesWithSpecialCharacters() throws IOException {
        // Note: Some characters may not be valid on all OS
        String fileName = "file-with_special.chars.txt";
        Path testFile = tempDir.resolve(fileName);
        Files.writeString(testFile, "content");

        assertTrue(tool.fileExists(fileName));
        assertEquals("content", tool.readFile(fileName));
    }

    @Test
    @DisplayName("should handle large file content")
    void shouldHandleLargeFileContent() throws IOException {
        // Generate ~1MB content
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("Line ").append(i).append(": This is some content to make the file larger.\n");
        }
        String largeContent = sb.toString();

        String result = tool.writeFile("large.txt", largeContent);
        assertTrue(result.contains("SUCCESS"));

        String read = tool.readFile("large.txt");
        assertEquals(largeContent, read);
    }
}
