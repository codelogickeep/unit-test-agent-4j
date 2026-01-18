package com.codelogickeep.agent.ut.framework;

import com.codelogickeep.agent.ut.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class SimpleAgentOrchestratorTest {

    private SimpleAgentOrchestrator createOrchestrator() {
        AppConfig config = new AppConfig();
        AppConfig.LlmConfig llmConfig = new AppConfig.LlmConfig();
        llmConfig.setProtocol("openai");
        llmConfig.setApiKey("test-key");
        llmConfig.setModelName("test-model");
        config.setLlm(llmConfig);

        // 设置 WorkflowConfig 以避免 NPE
        AppConfig.WorkflowConfig workflowConfig = new AppConfig.WorkflowConfig();
        workflowConfig.setEnablePhaseSwitching(false); // 默认关闭阶段切换
        config.setWorkflow(workflowConfig);

        return new SimpleAgentOrchestrator(config, new ArrayList<>());
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
