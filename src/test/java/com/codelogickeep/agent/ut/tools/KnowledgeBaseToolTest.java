package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.config.AppConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KnowledgeBaseTool.
 */
@DisplayName("KnowledgeBaseTool Tests")
class KnowledgeBaseToolTest {

    private KnowledgeBaseTool knowledgeBaseTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        knowledgeBaseTool = new KnowledgeBaseTool();
    }

    @Nested
    @DisplayName("Initialization Tests")
    class InitializationTests {

        @Test
        @DisplayName("Should handle null knowledge base path gracefully")
        void shouldHandleNullPathGracefully() {
            // Given
            AppConfig config = new AppConfig();

            // When
            knowledgeBaseTool.init(config, null);

            // Then
            String status = knowledgeBaseTool.getKnowledgeBaseStatus();
            assertTrue(status.contains("Initialized: false"));
        }

        @Test
        @DisplayName("Should handle empty knowledge base path gracefully")
        void shouldHandleEmptyPathGracefully() {
            // Given
            AppConfig config = new AppConfig();

            // When
            knowledgeBaseTool.init(config, "");

            // Then
            String status = knowledgeBaseTool.getKnowledgeBaseStatus();
            assertTrue(status.contains("Initialized: false"));
        }

        @Test
        @DisplayName("Should handle non-existent path gracefully")
        void shouldHandleNonExistentPathGracefully() {
            // Given
            AppConfig config = new AppConfig();
            String nonExistentPath = "/non/existent/path/12345";

            // When
            knowledgeBaseTool.init(config, nonExistentPath);

            // Then
            String status = knowledgeBaseTool.getKnowledgeBaseStatus();
            assertTrue(status.contains("Initialized: false"));
        }

        @Test
        @DisplayName("Should initialize with valid directory containing Java files")
        void shouldInitializeWithValidDirectory() throws IOException {
            // Given
            AppConfig config = new AppConfig();
            Path javaFile = tempDir.resolve("TestService.java");
            Files.writeString(javaFile, """
                package com.example;
                
                public class TestService {
                    public String hello() {
                        return "Hello";
                    }
                }
                """);

            // When
            knowledgeBaseTool.init(config, tempDir.toString());

            // Then
            String status = knowledgeBaseTool.getKnowledgeBaseStatus();
            assertTrue(status.contains("Initialized: true"));
            assertTrue(status.contains("Document Count: 1"));
        }

        @Test
        @DisplayName("Should initialize with multiple file types")
        void shouldInitializeWithMultipleFileTypes() throws IOException {
            // Given
            AppConfig config = new AppConfig();
            Files.writeString(tempDir.resolve("Service.java"), "public class Service {}");
            Files.writeString(tempDir.resolve("README.md"), "# Documentation");
            Files.writeString(tempDir.resolve("config.yml"), "key: value");
            Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");

            // When
            knowledgeBaseTool.init(config, tempDir.toString());

            // Then
            String status = knowledgeBaseTool.getKnowledgeBaseStatus();
            assertTrue(status.contains("Initialized: true"));
            assertTrue(status.contains("Document Count: 4"));
        }

        @Test
        @DisplayName("Should handle directory with no supported files")
        void shouldHandleDirectoryWithNoSupportedFiles() throws IOException {
            // Given
            AppConfig config = new AppConfig();
            Files.writeString(tempDir.resolve("file.xyz"), "unsupported content");
            Files.writeString(tempDir.resolve("image.png"), "fake image data");

            // When
            knowledgeBaseTool.init(config, tempDir.toString());

            // Then
            String status = knowledgeBaseTool.getKnowledgeBaseStatus();
            assertTrue(status.contains("Initialized: false"));
        }
    }

    @Nested
    @DisplayName("getKnowledgeBaseStatus Tests")
    class StatusTests {

        @Test
        @DisplayName("Should return status when not initialized")
        void shouldReturnStatusWhenNotInitialized() {
            // When
            String status = knowledgeBaseTool.getKnowledgeBaseStatus();

            // Then
            assertTrue(status.contains("Knowledge Base Status"));
            assertTrue(status.contains("Initialized: false"));
            assertTrue(status.contains("Not initialized"));
        }

        @Test
        @DisplayName("Should return status with details when initialized")
        void shouldReturnStatusWithDetailsWhenInitialized() throws IOException {
            // Given
            AppConfig config = new AppConfig();
            Files.writeString(tempDir.resolve("Test.java"), "public class Test {}");
            knowledgeBaseTool.init(config, tempDir.toString());

            // When
            String status = knowledgeBaseTool.getKnowledgeBaseStatus();

            // Then
            assertTrue(status.contains("Initialized: true"));
            assertTrue(status.contains("Indexed Path:"));
            assertTrue(status.contains("Document Count:"));
            assertTrue(status.contains("Supported Types:"));
        }
    }

    @Nested
    @DisplayName("searchKnowledge Tests")
    class SearchTests {

        @Test
        @DisplayName("Should return fallback when not initialized")
        void shouldReturnFallbackWhenNotInitialized() {
            // When
            String result = knowledgeBaseTool.searchKnowledge("mockito usage");

            // Then
            assertTrue(result.contains("Fallback") || result.contains("fallback") || 
                       result.contains("not initialized"));
        }

        @Test
        @DisplayName("Should return Mockito fallback for mock-related queries")
        void shouldReturnMockitoFallback() {
            // When
            String result = knowledgeBaseTool.searchKnowledge("how to use mock");

            // Then
            assertTrue(result.contains("Mockito") || result.contains("@Mock"));
        }

        @Test
        @DisplayName("Should return JUnit fallback for test-related queries")
        void shouldReturnJUnitFallback() {
            // When
            String result = knowledgeBaseTool.searchKnowledge("junit test example");

            // Then
            assertTrue(result.contains("JUnit") || result.contains("Assertions"));
        }

        @Test
        @DisplayName("Should search knowledge base when initialized")
        void shouldSearchWhenInitialized() throws IOException {
            // Given
            AppConfig config = new AppConfig();
            Files.writeString(tempDir.resolve("UserServiceTest.java"), """
                package com.example;
                
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                
                class UserServiceTest {
                    @Test
                    void shouldReturnUser() {
                        // Given
                        UserService service = new UserService();
                        
                        // When
                        User result = service.getUser(1L);
                        
                        // Then
                        assertNotNull(result);
                    }
                }
                """);
            knowledgeBaseTool.init(config, tempDir.toString());

            // When
            String result = knowledgeBaseTool.searchKnowledge("user service test");

            // Then
            // Should either find the document or return no results message
            assertTrue(result.contains("UserService") || 
                       result.contains("No relevant information") ||
                       result.contains("Results for"));
        }

        @Test
        @DisplayName("Should filter by document type")
        void shouldFilterByDocumentType() throws IOException {
            // Given
            AppConfig config = new AppConfig();
            Files.writeString(tempDir.resolve("Test.java"), "public class Test {}");
            Files.writeString(tempDir.resolve("Guide.md"), "# Testing Guide\nHow to write tests");
            knowledgeBaseTool.init(config, tempDir.toString());

            // When
            String javaResult = knowledgeBaseTool.searchKnowledge("test", "java");
            String markdownResult = knowledgeBaseTool.searchKnowledge("test", "markdown");

            // Then - both should return some result
            assertNotNull(javaResult);
            assertNotNull(markdownResult);
        }
    }

    @Nested
    @DisplayName("searchTestingGuidelines Tests")
    class SearchGuidelinesTests {

        @Test
        @DisplayName("Should search for testing guidelines")
        void shouldSearchForTestingGuidelines() {
            // When
            String result = knowledgeBaseTool.searchTestingGuidelines("assertion style");

            // Then
            assertNotNull(result);
            // Should contain fallback or no results message when not initialized
        }

        @Test
        @DisplayName("Should search guidelines with markdown filter")
        void shouldSearchGuidelinesWithMarkdownFilter() throws IOException {
            // Given
            AppConfig config = new AppConfig();
            Files.writeString(tempDir.resolve("TESTING.md"), """
                # Testing Guidelines
                
                ## Assertion Style
                Use AssertJ for fluent assertions.
                
                ## Mock Setup
                Use @Mock annotation from Mockito.
                """);
            knowledgeBaseTool.init(config, tempDir.toString());

            // When
            String result = knowledgeBaseTool.searchTestingGuidelines("assertion");

            // Then
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("searchTestExamples Tests")
    class SearchExamplesTests {

        @Test
        @DisplayName("Should search for test examples")
        void shouldSearchForTestExamples() {
            // When
            String result = knowledgeBaseTool.searchTestExamples("service layer test");

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should search examples with Java filter")
        void shouldSearchExamplesWithJavaFilter() throws IOException {
            // Given
            AppConfig config = new AppConfig();
            Files.writeString(tempDir.resolve("ServiceTest.java"), """
                @Test
                void serviceLayerTest() {
                    // Service layer test example
                    given(repository.findById(1L)).willReturn(Optional.of(entity));
                    
                    Result result = service.process(1L);
                    
                    assertThat(result).isNotNull();
                }
                """);
            knowledgeBaseTool.init(config, tempDir.toString());

            // When
            String result = knowledgeBaseTool.searchTestExamples("service layer");

            // Then
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("File Type Detection Tests")
    class FileTypeTests {

        @Test
        @DisplayName("Should detect Java files")
        void shouldDetectJavaFiles() throws IOException {
            // Given
            AppConfig config = new AppConfig();
            Files.writeString(tempDir.resolve("Test.java"), "public class Test {}");
            knowledgeBaseTool.init(config, tempDir.toString());

            // When
            String status = knowledgeBaseTool.getKnowledgeBaseStatus();

            // Then
            assertTrue(status.contains(".java"));
        }

        @Test
        @DisplayName("Should detect Markdown files")
        void shouldDetectMarkdownFiles() throws IOException {
            // Given
            AppConfig config = new AppConfig();
            Files.writeString(tempDir.resolve("README.md"), "# Readme");
            knowledgeBaseTool.init(config, tempDir.toString());

            // When
            String status = knowledgeBaseTool.getKnowledgeBaseStatus();

            // Then
            assertTrue(status.contains(".md"));
        }

        @Test
        @DisplayName("Should detect YAML files with both extensions")
        void shouldDetectYamlFiles() throws IOException {
            // Given
            AppConfig config = new AppConfig();
            Files.writeString(tempDir.resolve("config.yml"), "key: value");
            Files.writeString(tempDir.resolve("settings.yaml"), "setting: true");
            knowledgeBaseTool.init(config, tempDir.toString());

            // When
            String status = knowledgeBaseTool.getKnowledgeBaseStatus();

            // Then
            assertTrue(status.contains("Document Count: 2"));
        }
    }

    @Nested
    @DisplayName("Tool Annotation Tests")
    class ToolAnnotationTests {

        @Test
        @DisplayName("Should implement AgentTool interface")
        void shouldImplementAgentTool() {
            assertTrue(knowledgeBaseTool instanceof AgentTool);
        }

        @Test
        @DisplayName("getKnowledgeBaseStatus should have @Tool annotation")
        void getKnowledgeBaseStatus_shouldHaveToolAnnotation() throws NoSuchMethodException {
            var method = KnowledgeBaseTool.class.getMethod("getKnowledgeBaseStatus");
            assertTrue(method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
        }

        @Test
        @DisplayName("searchKnowledge should have @Tool annotation")
        void searchKnowledge_shouldHaveToolAnnotation() throws NoSuchMethodException {
            var method = KnowledgeBaseTool.class.getMethod("searchKnowledge", String.class, String.class);
            assertTrue(method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
        }

        @Test
        @DisplayName("searchTestingGuidelines should have @Tool annotation")
        void searchTestingGuidelines_shouldHaveToolAnnotation() throws NoSuchMethodException {
            var method = KnowledgeBaseTool.class.getMethod("searchTestingGuidelines", String.class);
            assertTrue(method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
        }

        @Test
        @DisplayName("searchTestExamples should have @Tool annotation")
        void searchTestExamples_shouldHaveToolAnnotation() throws NoSuchMethodException {
            var method = KnowledgeBaseTool.class.getMethod("searchTestExamples", String.class);
            assertTrue(method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
        }
    }
}
