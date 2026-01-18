package com.codelogickeep.agent.ut.framework.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixPromptBuilderTest {
    
    @Test
    void testBuildGenerateTestPrompt() {
        String prompt = FixPromptBuilder.buildGenerateTestPrompt(
                "src/main/java/com/example/Calculator.java",
                "add",
                "src/test/java/com/example/CalculatorTest.java",
                50.0);
        
        assertNotNull(prompt);
        assertTrue(prompt.contains("src/main/java/com/example/Calculator.java"));
        assertTrue(prompt.contains("`add`"));
        assertTrue(prompt.contains("src/test/java/com/example/CalculatorTest.java"));
        assertTrue(prompt.contains("50.0%"));
        assertTrue(prompt.contains("生成测试代码"));
        assertTrue(prompt.contains("代码已写入"));
    }
    
    @Test
    void testBuildSyntaxFixPrompt() {
        String prompt = FixPromptBuilder.buildSyntaxFixPrompt(
                "src/test/java/com/example/CalculatorTest.java",
                "Line 10: missing semicolon");
        
        assertNotNull(prompt);
        assertTrue(prompt.contains("src/test/java/com/example/CalculatorTest.java"));
        assertTrue(prompt.contains("missing semicolon"));
        assertTrue(prompt.contains("修复语法错误"));
        assertTrue(prompt.contains("已修复"));
    }
    
    @Test
    void testBuildLspFixPrompt() {
        String prompt = FixPromptBuilder.buildLspFixPrompt(
                "src/test/java/com/example/CalculatorTest.java",
                "Calculator cannot be resolved to a type");
        
        assertNotNull(prompt);
        assertTrue(prompt.contains("src/test/java/com/example/CalculatorTest.java"));
        assertTrue(prompt.contains("cannot be resolved to a type"));
        assertTrue(prompt.contains("LSP 语义错误"));
        assertTrue(prompt.contains("添加缺少的 import"));
    }
    
    @Test
    void testBuildCompileFixPrompt() {
        String prompt = FixPromptBuilder.buildCompileFixPrompt(
                "src/test/java/com/example/CalculatorTest.java",
                "[ERROR] COMPILATION ERROR : missing import");
        
        assertNotNull(prompt);
        assertTrue(prompt.contains("src/test/java/com/example/CalculatorTest.java"));
        assertTrue(prompt.contains("COMPILATION ERROR"));
        assertTrue(prompt.contains("修复编译错误"));
        assertTrue(prompt.contains("缺少 import 语句"));
    }
    
    @Test
    void testBuildTestFixPrompt() {
        String prompt = FixPromptBuilder.buildTestFixPrompt(
                "src/test/java/com/example/CalculatorTest.java",
                "com.example.CalculatorTest",
                "AssertionError: expected 5 but was 4");
        
        assertNotNull(prompt);
        assertTrue(prompt.contains("src/test/java/com/example/CalculatorTest.java"));
        assertTrue(prompt.contains("com.example.CalculatorTest"));
        assertTrue(prompt.contains("AssertionError"));
        assertTrue(prompt.contains("修复测试失败"));
        assertTrue(prompt.contains("断言失败"));
    }
    
    @Test
    void testBuildMoreTestsPrompt() {
        String prompt = FixPromptBuilder.buildMoreTestsPrompt(
                "src/main/java/com/example/Calculator.java",
                "divide",
                "src/test/java/com/example/CalculatorTest.java",
                60.0,
                80);
        
        assertNotNull(prompt);
        assertTrue(prompt.contains("`divide`"));
        assertTrue(prompt.contains("60.0%"));
        assertTrue(prompt.contains("80%"));
        assertTrue(prompt.contains("20.0%")); // 差距
        assertTrue(prompt.contains("覆盖率不足"));
        assertTrue(prompt.contains("边界条件"));
    }
    
    @Test
    void testTruncateErrorWithNull() {
        // 通过 buildSyntaxFixPrompt 间接测试 truncateError
        String prompt = FixPromptBuilder.buildSyntaxFixPrompt(
                "test.java",
                null);
        
        assertTrue(prompt.contains("无详细信息"));
    }
    
    @Test
    void testTruncateErrorWithLongMessage() {
        // 创建一个超过 2000 字符的错误信息
        StringBuilder longError = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longError.append("Error line ").append(i).append("\n");
        }
        
        String prompt = FixPromptBuilder.buildSyntaxFixPrompt(
                "test.java",
                longError.toString());
        
        // 验证错误信息被截断
        assertTrue(prompt.contains("错误信息已截断"));
    }
    
    @Test
    void testPromptContainsInstructions() {
        String prompt = FixPromptBuilder.buildGenerateTestPrompt(
                "Test.java", "test", "TestTest.java", 0);
        
        // 验证包含关键指令
        assertTrue(prompt.contains("readFile"));
        assertTrue(prompt.contains("writeFileFromLine"));
        assertTrue(prompt.contains("不要"));
        assertTrue(prompt.contains("checkSyntax"));
    }
}
