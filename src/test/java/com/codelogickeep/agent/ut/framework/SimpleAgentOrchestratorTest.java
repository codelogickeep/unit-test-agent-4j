package com.codelogickeep.agent.ut.framework;

import com.codelogickeep.agent.ut.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimpleAgentOrchestratorTest {

    private SimpleAgentOrchestrator createOrchestrator() {
        AppConfig config = new AppConfig();
        AppConfig.LlmConfig llmConfig = new AppConfig.LlmConfig();
        llmConfig.setProtocol("openai");
        llmConfig.setApiKey("test-key");
        llmConfig.setModelName("test-model");
        config.setLlm(llmConfig);
        return new SimpleAgentOrchestrator(config, new ArrayList<>());
    }

    @Test
    void testCalculateTestFilePath_RelativePath() throws Exception {
        // Arrange
        SimpleAgentOrchestrator orchestrator = createOrchestrator();
        Method method = SimpleAgentOrchestrator.class.getDeclaredMethod("calculateTestFilePath", String.class);
        method.setAccessible(true);

        String sourceFile = "src/main/java/com/example/Calculator.java";

        // Act
        String result = (String) method.invoke(orchestrator, sourceFile);

        // Assert
        // Result should be absolute path ending with
        // src/test/java/com/example/CalculatorTest.java
        assertTrue(result.endsWith("src/test/java/com/example/CalculatorTest.java"));
        assertTrue(result.contains("src" + File.separator + "test"));
    }

    @Test
    void testCalculateTestFilePath_AbsolutePath() throws Exception {
        // Arrange
        SimpleAgentOrchestrator orchestrator = createOrchestrator();
        Method method = SimpleAgentOrchestrator.class.getDeclaredMethod("calculateTestFilePath", String.class);
        method.setAccessible(true);

        Path absPath = Paths.get("src/main/java/com/example/Calculator.java").toAbsolutePath();
        String sourceFile = absPath.toString();

        // Act
        String result = (String) method.invoke(orchestrator, sourceFile);

        // Assert
        assertTrue(result.endsWith("src/test/java/com/example/CalculatorTest.java"));
        // Should handle platform specific separators
        // Note: The method normalizes to forward slashes internally, so we check for
        // that
        assertTrue(result.replace("\\", "/").endsWith("src/test/java/com/example/CalculatorTest.java"));
    }

    @Test
    void testExtractMethodNamesFromAnalysis() throws Exception {
        // Arrange
        SimpleAgentOrchestrator orchestrator = createOrchestrator();
        Method method = SimpleAgentOrchestrator.class.getDeclaredMethod("extractMethodNamesFromAnalysis", String.class);
        method.setAccessible(true);

        // 使用 analyzeClass 的真实输出格式
        String analysisResult = """
                Class: Calculator
                Public Methods:
                  - Signature: add(int, int)
                    ReturnType: int
                  - Signature: subtract(int, int)
                    ReturnType: int
                  - Signature: multiply(int, int)
                    ReturnType: int
                  - Signature: divide(int, int)
                    ReturnType: int
                  - Signature: toString()
                    ReturnType: String
                """;
        
        // Act
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(orchestrator, analysisResult);
        
        // Assert
        assertNotNull(result);
        // Debug output to see what was actually extracted
        System.out.println("Extracted methods: " + result);
        
        assertTrue(result.contains("add"), "Should contain 'add'");
        assertTrue(result.contains("subtract"), "Should contain 'subtract'");
        assertTrue(result.contains("multiply"), "Should contain 'multiply'");
        assertTrue(result.contains("divide"), "Should contain 'divide'");
        
        // Should exclude standard methods
        assertFalse(result.contains("toString"));
        
        assertEquals(4, result.size());
    }

    @Test
    void testExtractClassName_Path() throws Exception {
        // Arrange
        SimpleAgentOrchestrator orchestrator = createOrchestrator();
        Method method = SimpleAgentOrchestrator.class.getDeclaredMethod("extractClassName", String.class);
        method.setAccessible(true);

        String sourceFile = "/Users/test/project/src/main/java/com/example/MyClass.java";
        
        // Act
        String result = (String) method.invoke(orchestrator, sourceFile);
        
        // Assert
        assertEquals("com.example.MyClass", result);
    }

    @Test
    void testExtractClassName_FileContent(@TempDir Path tempDir) throws Exception {
        // Arrange
        SimpleAgentOrchestrator orchestrator = createOrchestrator();
        Method method = SimpleAgentOrchestrator.class.getDeclaredMethod("extractClassName", String.class);
        method.setAccessible(true);

        // 创建一个不在 src/main/java 目录下的文件
        Path sourceFile = tempDir.resolve("CustomClass.java");
        Files.writeString(sourceFile, """
                package com.custom;
                
                public class CustomClass {
                    public void method() {}
                }
                """);
        
        // Act
        String result = (String) method.invoke(orchestrator, sourceFile.toString());
        
        // Assert
        assertEquals("com.custom.CustomClass", result);
    }
    
    @Test
    void testExtractClassName_MultipleClasses(@TempDir Path tempDir) throws Exception {
        // Arrange
        SimpleAgentOrchestrator orchestrator = createOrchestrator();
        Method method = SimpleAgentOrchestrator.class.getDeclaredMethod("extractClassName", String.class);
        method.setAccessible(true);

        // 测试包含多个类的文件，应该优先选择 public 类
        Path sourceFile = tempDir.resolve("MultiClass.java");
        Files.writeString(sourceFile, """
                package com.multi;
                
                class Helper {}
                
                public class MultiClass {
                    public void method() {}
                }
                
                class AnotherHelper {}
                """);
        
        // Act
        String result = (String) method.invoke(orchestrator, sourceFile.toString());
        
        // Assert
        assertEquals("com.multi.MultiClass", result);
    }
}
