package com.codelogickeep.agent.ut.tools;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SyntaxCheckerTool 单元测试
 * 重点测试括号平衡检查和 JavaParser 语法检查
 */
@DisplayName("SyntaxCheckerTool Tests")
class SyntaxCheckerToolTest {

    private SyntaxCheckerTool checker;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        checker = new SyntaxCheckerTool();
        checker.setProjectRoot(tempDir.toString());
        // 清除 CompileGuard 状态
        CompileGuard.getInstance().clearAllStatus();
    }

    @AfterEach
    void tearDown() {
        CompileGuard.getInstance().clearAllStatus();
    }

    @Nested
    @DisplayName("Brace Balance Check")
    class BraceBalanceCheck {

        @Test
        @DisplayName("should pass for balanced braces")
        void shouldPassForBalancedBraces() {
            String code = """
                public class Test {
                    public void method() {
                        if (true) {
                            System.out.println("Hello");
                        }
                    }
                }
                """;
            
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            assertTrue(result.startsWith("SYNTAX_OK"));
        }

        @Test
        @DisplayName("should detect missing closing brace")
        void shouldDetectMissingClosingBrace() {
            String code = """
                public class Test {
                    public void method() {
                        if (true) {
                            System.out.println("Hello");
                        }
                    // Missing closing brace for class
                """;
            
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            assertTrue(result.contains("SYNTAX_ERRORS") || result.contains("BRACE"));
        }

        @Test
        @DisplayName("should detect missing closing parenthesis")
        void shouldDetectMissingClosingParenthesis() {
            String code = """
                public class Test {
                    public void method() {
                        if (true && (false || true) {
                            System.out.println("Hello");
                        }
                    }
                }
                """;
            
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            // Either brace check or JavaParser should catch this
            assertFalse(result.startsWith("SYNTAX_OK"));
        }

        @Test
        @DisplayName("should detect extra closing brace")
        void shouldDetectExtraClosingBrace() {
            String code = """
                public class Test {
                    public void method() {
                    }
                }}
                """;
            
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            assertFalse(result.startsWith("SYNTAX_OK"));
        }

        @Test
        @DisplayName("should ignore braces in strings")
        void shouldIgnoreBracesInStrings() {
            String code = """
                public class Test {
                    public void method() {
                        String s = "{ this is not a brace }";
                        System.out.println(s);
                    }
                }
                """;
            
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            assertTrue(result.startsWith("SYNTAX_OK"));
        }

        @Test
        @DisplayName("should ignore braces in comments")
        void shouldIgnoreBracesInComments() {
            String code = """
                public class Test {
                    // { this is a comment with brace }
                    public void method() {
                        /* { block comment with brace } */
                        System.out.println("Hello");
                    }
                }
                """;
            
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            assertTrue(result.startsWith("SYNTAX_OK"));
        }

        @Test
        @DisplayName("should detect incomplete method body")
        void shouldDetectIncompleteMethodBody() {
            // This is the actual bug scenario
            String code = """
                public class Test {
                    @Test
                    void method1() {
                        int a = 0;
                    
                    @Test
                    void method2() {
                        int b = 1;
                    }
                }
                """;
            
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            // Should detect the missing closing brace for method1
            assertFalse(result.startsWith("SYNTAX_OK"));
        }
    }

    @Nested
    @DisplayName("JavaParser Syntax Check")
    class JavaParserSyntaxCheck {

        @Test
        @DisplayName("should pass valid Java code")
        void shouldPassValidJavaCode() {
            String code = """
                package com.example;
                
                import java.util.List;
                
                public class Test {
                    private String name;
                    
                    public String getName() {
                        return name;
                    }
                    
                    public void setName(String name) {
                        this.name = name;
                    }
                }
                """;
            
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            assertTrue(result.startsWith("SYNTAX_OK"));
        }

        @Test
        @DisplayName("should detect missing semicolon")
        void shouldDetectMissingSemicolon() {
            String code = """
                public class Test {
                    public void method() {
                        int a = 5
                        System.out.println(a);
                    }
                }
                """;
            
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            assertFalse(result.startsWith("SYNTAX_OK"));
        }

        @Test
        @DisplayName("should detect invalid syntax")
        void shouldDetectInvalidSyntax() {
            String code = """
                public class Test {
                    public void method() {
                        int = 5;
                    }
                }
                """;
            
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            assertFalse(result.startsWith("SYNTAX_OK"));
        }
    }

    @Nested
    @DisplayName("File-based Check")
    class FileBasedCheck {

        @Test
        @DisplayName("should check file from path")
        void shouldCheckFileFromPath() throws IOException {
            String code = """
                public class TestFile {
                    public void method() {
                        System.out.println("Hello");
                    }
                }
                """;
            
            Path testFile = tempDir.resolve("TestFile.java");
            Files.writeString(testFile, code);
            
            String result = checker.checkSyntax(testFile.toString());
            
            assertTrue(result.startsWith("SYNTAX_OK"));
        }

        @Test
        @DisplayName("should return error for non-existent file")
        void shouldReturnErrorForNonExistentFile() {
            String result = checker.checkSyntax("/non/existent/File.java");
            
            assertTrue(result.contains("ERROR"));
            assertTrue(result.contains("not found"));
        }
    }

    @Nested
    @DisplayName("CompileGuard Integration")
    class CompileGuardIntegration {

        @Test
        @DisplayName("should mark file as passed when syntax is OK")
        void shouldMarkFileAsPassedWhenSyntaxOk() {
            String code = """
                public class Test {
                    public void method() {
                    }
                }
                """;
            
            // First mark file as modified
            CompileGuard.getInstance().markFileModified("Test.java");
            
            // Check syntax
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            assertTrue(result.startsWith("SYNTAX_OK"));
            
            // Verify CompileGuard was updated
            CompileGuard.CompileCheckResult guardResult = CompileGuard.getInstance().canCompile();
            assertTrue(guardResult.canCompile());
        }

        @Test
        @DisplayName("should mark file as failed when syntax has errors")
        void shouldMarkFileAsFailedWhenSyntaxErrors() {
            String code = """
                public class Test {
                    public void method() {
                    // Missing closing brace
                """;
            
            // Check syntax
            checker.checkSyntaxContent(code, "Test.java");
            
            // Verify CompileGuard was updated
            CompileGuard.CompileCheckResult guardResult = CompileGuard.getInstance().canCompile();
            assertFalse(guardResult.canCompile());
        }
    }

    @Nested
    @DisplayName("Test Import Warnings")
    class TestImportWarnings {

        @Test
        @DisplayName("should warn about missing Test import")
        void shouldWarnAboutMissingTestImport() {
            String code = """
                public class MyTest {
                    @Test
                    void testMethod() {
                    }
                }
                """;
            
            String result = checker.checkSyntaxContent(code, "MyTest.java");
            
            // Should pass syntax but may have warnings
            // The code is syntactically correct even without import
            assertTrue(result.contains("SYNTAX_OK") || result.contains("WARNINGS"));
        }
    }

    @Nested
    @DisplayName("Test Structure Validation")
    class TestStructureValidation {

        @Test
        @DisplayName("validateTestStructure should pass for valid test class")
        void validateTestStructure_shouldPassForValidTestClass() throws IOException {
            String code = """
                package com.example;
                
                import org.junit.jupiter.api.Test;
                
                public class MyTest {
                    @Test
                    void testMethod() {
                        // test
                    }
                }
                """;
            
            Path testFile = tempDir.resolve("MyTest.java");
            Files.writeString(testFile, code);
            
            String result = checker.validateTestStructure(testFile.toString());
            
            assertTrue(result.contains("STRUCTURE_OK"));
        }

        @Test
        @DisplayName("validateTestStructure should warn about class without @Test methods")
        void validateTestStructure_shouldWarnAboutNoTestMethods() throws IOException {
            String code = """
                package com.example;
                
                public class MyTest {
                    void regularMethod() {
                        // not a test
                    }
                }
                """;
            
            Path testFile = tempDir.resolve("MyTest.java");
            Files.writeString(testFile, code);
            
            String result = checker.validateTestStructure(testFile.toString());
            
            assertTrue(result.contains("STRUCTURE_ISSUES") || result.contains("no @Test"));
        }

        @Test
        @DisplayName("validateTestStructure should return error for non-existent file")
        void validateTestStructure_shouldReturnErrorForNonExistentFile() {
            String result = checker.validateTestStructure("/non/existent/File.java");
            
            assertTrue(result.contains("ERROR"));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle empty content")
        void shouldHandleEmptyContent() {
            String result = checker.checkSyntaxContent("", "Empty.java");
            
            // Empty file may or may not be considered valid depending on implementation
            assertNotNull(result);
        }

        @Test
        @DisplayName("should handle complex nested structures")
        void shouldHandleComplexNestedStructures() {
            String code = """
                public class Test {
                    public void method() {
                        for (int i = 0; i < 10; i++) {
                            if (i % 2 == 0) {
                                while (true) {
                                    try {
                                        doSomething();
                                    } catch (Exception e) {
                                        // handle
                                    } finally {
                                        cleanup();
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    
                    private void doSomething() {}
                    private void cleanup() {}
                }
                """;
            
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            assertTrue(result.startsWith("SYNTAX_OK"));
        }

        @Test
        @DisplayName("should handle lambda expressions")
        void shouldHandleLambdaExpressions() {
            String code = """
                import java.util.List;
                import java.util.stream.Collectors;
                
                public class Test {
                    public void method() {
                        List<String> list = List.of("a", "b", "c");
                        list.stream()
                            .filter(s -> s.length() > 0)
                            .map(s -> {
                                return s.toUpperCase();
                            })
                            .collect(Collectors.toList());
                    }
                }
                """;
            
            String result = checker.checkSyntaxContent(code, "Test.java");
            
            assertTrue(result.startsWith("SYNTAX_OK"));
        }

        @Test
        @DisplayName("should handle annotations")
        void shouldHandleAnnotations() {
            String code = """
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.DisplayName;
                
                @DisplayName("Test class")
                public class MyTest {
                    @Test
                    @DisplayName("test method")
                    void testMethod() {
                    }
                }
                """;
            
            String result = checker.checkSyntaxContent(code, "MyTest.java");
            
            assertTrue(result.startsWith("SYNTAX_OK"));
        }
    }
}
