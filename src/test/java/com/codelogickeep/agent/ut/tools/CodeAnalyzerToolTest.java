package com.codelogickeep.agent.ut.tools;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CodeAnalyzerTool.
 * Tests AST parsing and code analysis functionality.
 */
class CodeAnalyzerToolTest {

    private CodeAnalyzerTool tool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        tool = new CodeAnalyzerTool();
    }

    // ==================== analyzeClass Tests ====================

    @Test
    @DisplayName("analyzeClass should parse simple class")
    void analyzeClass_shouldParseSimpleClass() throws IOException {
        String code = """
            package com.example;
            
            public class SimpleClass {
                private String name;
                private int count;
                
                public String getName() {
                    return name;
                }
                
                public void setName(String name) {
                    this.name = name;
                }
            }
            """;
        Path javaFile = tempDir.resolve("SimpleClass.java");
        Files.writeString(javaFile, code);

        String result = tool.analyzeClass(javaFile.toString());

        assertTrue(result.contains("Package: com.example"));
        assertTrue(result.contains("Class: SimpleClass"));
        assertTrue(result.contains("String"));
        assertTrue(result.contains("name"));
        assertTrue(result.contains("getName"));
        assertTrue(result.contains("setName"));
    }

    @Test
    @DisplayName("analyzeClass should identify dependencies from fields")
    void analyzeClass_shouldIdentifyDependencies() throws IOException {
        String code = """
            package com.example;
            
            import java.util.List;
            
            public class ServiceClass {
                private UserRepository userRepo;
                private OrderService orderService;
                private List<String> cache;
                
                public void process() {}
            }
            """;
        Path javaFile = tempDir.resolve("ServiceClass.java");
        Files.writeString(javaFile, code);

        String result = tool.analyzeClass(javaFile.toString());

        assertTrue(result.contains("Dependencies (Fields)"));
        assertTrue(result.contains("UserRepository"));
        assertTrue(result.contains("OrderService"));
        assertTrue(result.contains("List<String>") || result.contains("List"));
    }

    @Test
    @DisplayName("analyzeClass should list only public methods")
    void analyzeClass_shouldListOnlyPublicMethods() throws IOException {
        String code = """
            package com.example;
            
            public class MixedVisibility {
                public void publicMethod() {}
                protected void protectedMethod() {}
                void packageMethod() {}
                private void privateMethod() {}
            }
            """;
        Path javaFile = tempDir.resolve("MixedVisibility.java");
        Files.writeString(javaFile, code);

        String result = tool.analyzeClass(javaFile.toString());

        assertTrue(result.contains("publicMethod"));
        assertFalse(result.contains("privateMethod"));
    }

    @Test
    @DisplayName("analyzeClass should handle class with no fields")
    void analyzeClass_shouldHandleClassWithNoFields() throws IOException {
        String code = """
            package com.example;
            
            public class NoFields {
                public void doSomething() {}
            }
            """;
        Path javaFile = tempDir.resolve("NoFields.java");
        Files.writeString(javaFile, code);

        String result = tool.analyzeClass(javaFile.toString());

        assertTrue(result.contains("Class: NoFields"));
        assertTrue(result.contains("doSomething"));
    }

    @Test
    @DisplayName("analyzeClass should show method parameters")
    void analyzeClass_shouldShowMethodParameters() throws IOException {
        String code = """
            package com.example;
            
            public class WithParams {
                public void calculate(int a, int b) {}
                public String format(String template, Object... args) {}
            }
            """;
        Path javaFile = tempDir.resolve("WithParams.java");
        Files.writeString(javaFile, code);

        String result = tool.analyzeClass(javaFile.toString());

        assertTrue(result.contains("int a"));
        assertTrue(result.contains("int b"));
        assertTrue(result.contains("String template"));
    }

    @Test
    @DisplayName("analyzeClass should throw for non-existing file")
    void analyzeClass_shouldThrowForNonExistingFile() {
        assertThrows(IOException.class, 
            () -> tool.analyzeClass(tempDir.resolve("NonExistent.java").toString()));
    }

    @Test
    @DisplayName("analyzeClass should throw for invalid Java code")
    void analyzeClass_shouldThrowForInvalidJavaCode() throws IOException {
        Path invalidFile = tempDir.resolve("Invalid.java");
        Files.writeString(invalidFile, "This is not valid Java code!");

        assertThrows(Exception.class, () -> tool.analyzeClass(invalidFile.toString()));
    }

    // ==================== analyzeMethod Tests ====================

    @Test
    @DisplayName("analyzeMethod should return method details")
    void analyzeMethod_shouldReturnMethodDetails() throws IOException {
        String code = """
            package com.example;
            
            public class Calculator {
                public int add(int a, int b) {
                    return a + b;
                }
            }
            """;
        Path javaFile = tempDir.resolve("Calculator.java");
        Files.writeString(javaFile, code);

        String result = tool.analyzeMethod(javaFile.toString(), "add");

        assertTrue(result.contains("Method: add"));
        assertTrue(result.contains("Return Type: int"));
        assertTrue(result.contains("Cyclomatic Complexity"));
    }

    @Test
    @DisplayName("analyzeMethod should calculate complexity for conditionals")
    void analyzeMethod_shouldCalculateComplexityForConditionals() throws IOException {
        String code = """
            package com.example;
            
            public class ComplexClass {
                public int complex(int x) {
                    if (x > 0) {
                        if (x > 10) {
                            return 1;
                        }
                        return 2;
                    } else if (x < 0) {
                        return -1;
                    }
                    return 0;
                }
            }
            """;
        Path javaFile = tempDir.resolve("ComplexClass.java");
        Files.writeString(javaFile, code);

        String result = tool.analyzeMethod(javaFile.toString(), "complex");

        assertTrue(result.contains("Cyclomatic Complexity"));
        // Should have complexity > 1 due to if statements
        assertTrue(result.matches("(?s).*Cyclomatic Complexity: [3-9].*"));
    }

    @Test
    @DisplayName("analyzeMethod should identify method calls")
    void analyzeMethod_shouldIdentifyMethodCalls() throws IOException {
        String code = """
            package com.example;
            
            public class Caller {
                private Helper helper;
                
                public void doWork() {
                    helper.process();
                    System.out.println("Done");
                    validate();
                }
                
                private void validate() {}
            }
            """;
        Path javaFile = tempDir.resolve("Caller.java");
        Files.writeString(javaFile, code);

        String result = tool.analyzeMethod(javaFile.toString(), "doWork");

        assertTrue(result.contains("Method Calls"));
        assertTrue(result.contains("helper.process()"));
        assertTrue(result.contains("System.out.println()"));
    }

    @Test
    @DisplayName("analyzeMethod should return 'not found' for non-existing method")
    void analyzeMethod_shouldReturnNotFoundForNonExistingMethod() throws IOException {
        String code = """
            package com.example;
            
            public class Simple {
                public void exists() {}
            }
            """;
        Path javaFile = tempDir.resolve("Simple.java");
        Files.writeString(javaFile, code);

        String result = tool.analyzeMethod(javaFile.toString(), "notExists");

        assertTrue(result.contains("not found") || result.contains("Method not found"));
    }

    // ==================== getMethodsForTesting Tests ====================

    @Test
    @DisplayName("getMethodsForTesting should list testable methods with complexity")
    void getMethodsForTesting_shouldListTestableMethodsWithComplexity() throws IOException {
        String code = """
            package com.example;
            
            public class TestableClass {
                public TestableClass(String name) {}
                
                public void methodA() {}
                public int methodB(int x) { return x; }
                protected void protectedMethod() {}
                private void privateMethod() {}
            }
            """;
        Path javaFile = tempDir.resolve("TestableClass.java");
        Files.writeString(javaFile, code);

        String result = tool.getMethodsForTesting(javaFile.toString());

        assertTrue(result.contains("Class: TestableClass"));
        assertTrue(result.contains("methodA"));
        assertTrue(result.contains("methodB"));
        assertTrue(result.contains("protectedMethod"));
        assertTrue(result.contains("constructor"));
        assertTrue(result.contains("[complexity:"));
        assertFalse(result.contains("privateMethod"));
    }

    @Test
    @DisplayName("getMethodsForTesting should show total method count")
    void getMethodsForTesting_shouldShowTotalMethodCount() throws IOException {
        String code = """
            package com.example;
            
            public class CountTest {
                public void m1() {}
                public void m2() {}
                public void m3() {}
            }
            """;
        Path javaFile = tempDir.resolve("CountTest.java");
        Files.writeString(javaFile, code);

        String result = tool.getMethodsForTesting(javaFile.toString());

        assertTrue(result.contains("Total: 3 methods"));
    }

    @Test
    @DisplayName("getMethodsForTesting should handle class with no testable methods")
    void getMethodsForTesting_shouldHandleClassWithNoTestableMethods() throws IOException {
        String code = """
            package com.example;
            
            public class NoPublicMethods {
                private void privateOnly() {}
            }
            """;
        Path javaFile = tempDir.resolve("NoPublicMethods.java");
        Files.writeString(javaFile, code);

        String result = tool.getMethodsForTesting(javaFile.toString());

        assertTrue(result.contains("Total: 0 methods"));
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("should handle interface")
    void shouldHandleInterface() throws IOException {
        String code = """
            package com.example;
            
            public interface MyInterface {
                void doSomething();
                String process(String input);
            }
            """;
        Path javaFile = tempDir.resolve("MyInterface.java");
        Files.writeString(javaFile, code);

        String result = tool.analyzeClass(javaFile.toString());

        assertTrue(result.contains("MyInterface"));
        assertTrue(result.contains("doSomething"));
        assertTrue(result.contains("process"));
    }

    @Test
    @DisplayName("should handle enum")
    void shouldHandleEnum() throws IOException {
        String code = """
            package com.example;
            
            public enum Status {
                ACTIVE, INACTIVE, PENDING;
                
                public boolean isActive() {
                    return this == ACTIVE;
                }
            }
            """;
        Path javaFile = tempDir.resolve("Status.java");
        Files.writeString(javaFile, code);

        // Should not throw
        assertDoesNotThrow(() -> tool.analyzeClass(javaFile.toString()));
    }

    @Test
    @DisplayName("should handle generic class")
    void shouldHandleGenericClass() throws IOException {
        String code = """
            package com.example;
            
            import java.util.List;
            
            public class GenericClass<T> {
                private List<T> items;
                
                public void add(T item) {}
                public T get(int index) { return null; }
            }
            """;
        Path javaFile = tempDir.resolve("GenericClass.java");
        Files.writeString(javaFile, code);

        String result = tool.analyzeClass(javaFile.toString());

        assertTrue(result.contains("GenericClass"));
        assertTrue(result.contains("List<T>") || result.contains("List"));
    }
}
