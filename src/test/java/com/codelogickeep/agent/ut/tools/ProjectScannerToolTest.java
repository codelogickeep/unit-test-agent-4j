package com.codelogickeep.agent.ut.tools;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProjectScannerTool.
 */
@DisplayName("ProjectScannerTool Tests")
class ProjectScannerToolTest {

    private ProjectScannerTool projectScannerTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        projectScannerTool = new ProjectScannerTool();
    }

    @Nested
    @DisplayName("scanProjectClasses Tests")
    class ScanProjectClassesTests {

        @Test
        @DisplayName("Should return error when project path does not exist")
        void shouldReturnErrorWhenPathNotExists() throws IOException {
            // Given
            String nonExistentPath = "/non/existent/path/12345";

            // When
            String result = projectScannerTool.scanProjectClasses(nonExistentPath, null);

            // Then
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("does not exist"));
        }

        @Test
        @DisplayName("Should find Java files in src/main/java")
        void shouldFindJavaFilesInSrcMainJava() throws IOException {
            // Given - create project structure
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.writeString(srcMainJava.resolve("UserService.java"), "public class UserService {}");
            Files.writeString(srcMainJava.resolve("OrderService.java"), "public class OrderService {}");

            // When
            String result = projectScannerTool.scanProjectClasses(tempDir.toString(), null);

            // Then
            assertTrue(result.contains("Found 2 core source classes"));
            assertTrue(result.contains("UserService.java"));
            assertTrue(result.contains("OrderService.java"));
        }

        @Test
        @DisplayName("Should exclude test classes by default")
        void shouldExcludeTestClassesByDefault() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path srcTestJava = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(srcTestJava);

            Files.writeString(srcMainJava.resolve("Service.java"), "public class Service {}");
            Files.writeString(srcMainJava.resolve("ServiceTest.java"), "public class ServiceTest {}"); // Should be excluded
            Files.writeString(srcTestJava.resolve("TestHelper.java"), "public class TestHelper {}"); // In test folder

            // When
            String result = projectScannerTool.scanProjectClasses(tempDir.toString(), null);

            // Then
            assertTrue(result.contains("Service.java"));
            assertFalse(result.contains("ServiceTest.java")); // Excluded by pattern
        }

        @Test
        @DisplayName("Should exclude DTO/VO/TO classes by default")
        void shouldExcludeDtoVoToClasses() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path dtoDir = tempDir.resolve("src/main/java/com/example/dto");
            Path voDir = tempDir.resolve("src/main/java/com/example/vo");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(dtoDir);
            Files.createDirectories(voDir);

            Files.writeString(srcMainJava.resolve("UserService.java"), "public class UserService {}");
            Files.writeString(dtoDir.resolve("UserDTO.java"), "public class UserDTO {}");
            Files.writeString(voDir.resolve("UserVO.java"), "public class UserVO {}");
            Files.writeString(srcMainJava.resolve("UserTO.java"), "public class UserTO {}");

            // When
            String result = projectScannerTool.scanProjectClasses(tempDir.toString(), null);

            // Then
            assertTrue(result.contains("UserService.java"));
            assertFalse(result.contains("UserDTO.java"));
            assertFalse(result.contains("UserVO.java"));
            assertFalse(result.contains("UserTO.java"));
        }

        @Test
        @DisplayName("Should exclude domain/entity classes by default")
        void shouldExcludeDomainClasses() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path domainDir = tempDir.resolve("src/main/java/com/example/domain");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(domainDir);

            Files.writeString(srcMainJava.resolve("UserService.java"), "public class UserService {}");
            Files.writeString(domainDir.resolve("User.java"), "public class User {}");
            Files.writeString(domainDir.resolve("Order.java"), "public class Order {}");

            // When
            String result = projectScannerTool.scanProjectClasses(tempDir.toString(), null);

            // Then
            assertTrue(result.contains("UserService.java"));
            assertFalse(result.contains("domain/User.java"));
            assertFalse(result.contains("domain/Order.java"));
        }

        @Test
        @DisplayName("Should apply custom exclude patterns")
        void shouldApplyCustomExcludePatterns() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(srcMainJava);

            Files.writeString(srcMainJava.resolve("UserService.java"), "public class UserService {}");
            Files.writeString(srcMainJava.resolve("UserUtil.java"), "public class UserUtil {}");
            Files.writeString(srcMainJava.resolve("StringHelper.java"), "public class StringHelper {}");

            // When - exclude Util and Helper classes
            String result = projectScannerTool.scanProjectClasses(tempDir.toString(), ".*Util\\.java$,.*Helper\\.java$");

            // Then
            assertTrue(result.contains("UserService.java"));
            assertFalse(result.contains("UserUtil.java"));
            assertFalse(result.contains("StringHelper.java"));
        }

        @Test
        @DisplayName("Should handle empty exclude patterns")
        void shouldHandleEmptyExcludePatterns() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.writeString(srcMainJava.resolve("Service.java"), "public class Service {}");

            // When
            String result1 = projectScannerTool.scanProjectClasses(tempDir.toString(), "");
            String result2 = projectScannerTool.scanProjectClasses(tempDir.toString(), "   ");
            String result3 = projectScannerTool.scanProjectClasses(tempDir.toString(), null);

            // Then - all should work without error
            assertTrue(result1.contains("Service.java"));
            assertTrue(result2.contains("Service.java"));
            assertTrue(result3.contains("Service.java"));
        }

        @Test
        @DisplayName("Should return message when no classes found")
        void shouldReturnMessageWhenNoClassesFound() throws IOException {
            // Given - empty project
            Path srcMainJava = tempDir.resolve("src/main/java");
            Files.createDirectories(srcMainJava);

            // When
            String result = projectScannerTool.scanProjectClasses(tempDir.toString(), null);

            // Then
            assertTrue(result.contains("No core source classes found"));
        }

        @Test
        @DisplayName("Should exclude Constants and Enum files")
        void shouldExcludeConstantsAndEnums() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(srcMainJava);

            Files.writeString(srcMainJava.resolve("UserService.java"), "public class UserService {}");
            Files.writeString(srcMainJava.resolve("StatusEnum.java"), "public enum StatusEnum {}");
            Files.writeString(srcMainJava.resolve("AppConstants.java"), "public class AppConstants {}");

            // When
            String result = projectScannerTool.scanProjectClasses(tempDir.toString(), null);

            // Then
            assertTrue(result.contains("UserService.java"));
            assertFalse(result.contains("StatusEnum.java"));
            assertFalse(result.contains("AppConstants.java"));
        }
    }

    @Nested
    @DisplayName("getSourceClassPaths Tests")
    class GetSourceClassPathsTests {

        @Test
        @DisplayName("Should return empty list when path does not exist")
        void shouldReturnEmptyListWhenPathNotExists() throws IOException {
            // Given
            String nonExistentPath = "/non/existent/path/12345";

            // When
            List<String> result = projectScannerTool.getSourceClassPaths(nonExistentPath, null);

            // Then
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return list of Java file paths")
        void shouldReturnListOfJavaFilePaths() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.writeString(srcMainJava.resolve("UserService.java"), "public class UserService {}");
            Files.writeString(srcMainJava.resolve("OrderService.java"), "public class OrderService {}");

            // When
            List<String> result = projectScannerTool.getSourceClassPaths(tempDir.toString(), null);

            // Then
            assertEquals(2, result.size());
            assertTrue(result.stream().anyMatch(p -> p.contains("UserService.java")));
            assertTrue(result.stream().anyMatch(p -> p.contains("OrderService.java")));
        }

        @Test
        @DisplayName("Should normalize path separators to forward slashes")
        void shouldNormalizePathSeparators() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(srcMainJava);
            Files.writeString(srcMainJava.resolve("Service.java"), "public class Service {}");

            // When
            List<String> result = projectScannerTool.getSourceClassPaths(tempDir.toString(), null);

            // Then
            assertFalse(result.isEmpty());
            for (String path : result) {
                assertFalse(path.contains("\\"), "Path should use forward slashes: " + path);
            }
        }

        @Test
        @DisplayName("Should apply exclusion patterns to list result")
        void shouldApplyExclusionPatternsToList() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path dtoDir = tempDir.resolve("src/main/java/com/example/dto");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(dtoDir);

            Files.writeString(srcMainJava.resolve("UserService.java"), "public class UserService {}");
            Files.writeString(dtoDir.resolve("UserDTO.java"), "public class UserDTO {}");

            // When
            List<String> result = projectScannerTool.getSourceClassPaths(tempDir.toString(), null);

            // Then
            assertEquals(1, result.size());
            assertTrue(result.get(0).contains("UserService.java"));
        }
    }

    @Nested
    @DisplayName("Pattern Matching Tests")
    class PatternMatchingTests {

        @Test
        @DisplayName("Should exclude DAO and Repo classes")
        void shouldExcludeDaoAndRepoClasses() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Path daoDir = tempDir.resolve("src/main/java/com/example/dao");
            Path repoDir = tempDir.resolve("src/main/java/com/example/repo");
            Files.createDirectories(srcMainJava);
            Files.createDirectories(daoDir);
            Files.createDirectories(repoDir);

            Files.writeString(srcMainJava.resolve("UserService.java"), "public class UserService {}");
            Files.writeString(daoDir.resolve("UserDAO.java"), "public class UserDAO {}");
            Files.writeString(repoDir.resolve("UserRepo.java"), "public interface UserRepo {}");
            Files.writeString(repoDir.resolve("UserRepoImpl.java"), "public class UserRepoImpl {}");

            // When
            String result = projectScannerTool.scanProjectClasses(tempDir.toString(), null);

            // Then
            assertTrue(result.contains("UserService.java"));
            assertFalse(result.contains("UserDAO.java"));
            assertFalse(result.contains("UserRepo.java"));
            assertFalse(result.contains("UserRepoImpl.java"));
        }

        @Test
        @DisplayName("Should handle multiple custom patterns separated by comma")
        void shouldHandleMultipleCustomPatterns() throws IOException {
            // Given
            Path srcMainJava = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(srcMainJava);

            Files.writeString(srcMainJava.resolve("UserService.java"), "");
            Files.writeString(srcMainJava.resolve("UserConfig.java"), "");
            Files.writeString(srcMainJava.resolve("UserFactory.java"), "");
            Files.writeString(srcMainJava.resolve("UserBuilder.java"), "");

            // When - exclude Config, Factory, and Builder
            String result = projectScannerTool.scanProjectClasses(tempDir.toString(), 
                    ".*Config\\.java$, .*Factory\\.java$, .*Builder\\.java$");

            // Then
            assertTrue(result.contains("UserService.java"));
            assertFalse(result.contains("UserConfig.java"));
            assertFalse(result.contains("UserFactory.java"));
            assertFalse(result.contains("UserBuilder.java"));
        }
    }

    @Nested
    @DisplayName("Tool Annotation Tests")
    class ToolAnnotationTests {

        @Test
        @DisplayName("Should implement AgentTool interface")
        void shouldImplementAgentTool() {
            assertTrue(projectScannerTool instanceof AgentTool);
        }

        @Test
        @DisplayName("scanProjectClasses should have @Tool annotation")
        void scanProjectClasses_shouldHaveToolAnnotation() throws NoSuchMethodException {
            var method = ProjectScannerTool.class.getMethod("scanProjectClasses", String.class, String.class);
            assertTrue(method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
        }

        @Test
        @DisplayName("getSourceClassPaths should have @Tool annotation")
        void getSourceClassPaths_shouldHaveToolAnnotation() throws NoSuchMethodException {
            var method = ProjectScannerTool.class.getMethod("getSourceClassPaths", String.class, String.class);
            assertTrue(method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
        }
    }
}
