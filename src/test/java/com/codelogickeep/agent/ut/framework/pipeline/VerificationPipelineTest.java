package com.codelogickeep.agent.ut.framework.pipeline;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationPipelineTest {
    
    @Mock
    private ToolRegistry toolRegistry;
    
    @Mock
    private AppConfig config;
    
    @Mock
    private AppConfig.WorkflowConfig workflowConfig;
    
    private VerificationPipeline pipeline;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(config.getWorkflow()).thenReturn(workflowConfig);
        when(workflowConfig.isUseLsp()).thenReturn(false);
        when(workflowConfig.getCoverageThreshold()).thenReturn(80);
        pipeline = new VerificationPipeline(toolRegistry, config);
    }
    
    @Test
    void testExecuteAllStepsPass() throws Exception {
        // Mock all tools to return success
        when(toolRegistry.invoke(eq("checkSyntax"), any())).thenReturn("VALID: No errors");
        when(toolRegistry.invoke(eq("compileProject"), any())).thenReturn("BUILD SUCCESS exitCode=0");
        when(toolRegistry.invoke(eq("executeTest"), any())).thenReturn("Tests run: 5, Failures: 0, Errors: 0");
        when(toolRegistry.invoke(eq("getSingleMethodCoverage"), any())).thenReturn("method line=85.0%");
        
        VerificationResult result = pipeline.execute(
                "src/test/java/Test.java",
                "com.example.Test",
                "com.example.Calculator",
                "add",
                ".");
        
        assertTrue(result.isSuccess());
        assertEquals(85.0, result.getCoverage(), 0.1);
        assertTrue(result.isCoverageThresholdMet());
    }
    
    @Test
    void testExecuteSyntaxCheckFails() throws Exception {
        when(toolRegistry.invoke(eq("checkSyntax"), any()))
                .thenReturn("ERROR: Syntax error at line 10");
        
        VerificationResult result = pipeline.execute(
                "src/test/java/Test.java",
                "com.example.Test",
                "com.example.Calculator",
                "add",
                ".");
        
        assertFalse(result.isSuccess());
        assertEquals(VerificationStep.SYNTAX_CHECK, result.getFailedStep());
        assertNotNull(result.getErrorDetails());
    }
    
    @Test
    void testExecuteCompileFails() throws Exception {
        when(toolRegistry.invoke(eq("checkSyntax"), any())).thenReturn("VALID");
        when(toolRegistry.invoke(eq("compileProject"), any()))
                .thenReturn("BUILD FAILURE exitCode=1 COMPILATION ERROR");
        
        VerificationResult result = pipeline.execute(
                "src/test/java/Test.java",
                "com.example.Test",
                "com.example.Calculator",
                "add",
                ".");
        
        assertFalse(result.isSuccess());
        assertEquals(VerificationStep.COMPILE, result.getFailedStep());
    }
    
    @Test
    void testExecuteTestFails() throws Exception {
        when(toolRegistry.invoke(eq("checkSyntax"), any())).thenReturn("VALID");
        when(toolRegistry.invoke(eq("compileProject"), any())).thenReturn("BUILD SUCCESS");
        when(toolRegistry.invoke(eq("executeTest"), any()))
                .thenReturn("BUILD FAILURE Tests run: 5, Failures: 2, Errors: 0");
        
        VerificationResult result = pipeline.execute(
                "src/test/java/Test.java",
                "com.example.Test",
                "com.example.Calculator",
                "add",
                ".");
        
        assertFalse(result.isSuccess());
        assertEquals(VerificationStep.TEST, result.getFailedStep());
        assertNotNull(result.getErrorMessage());
    }
    
    @Test
    void testExecuteWithLspEnabled() throws Exception {
        // 启用 LSP
        when(workflowConfig.isUseLsp()).thenReturn(true);
        pipeline = new VerificationPipeline(toolRegistry, config);
        
        when(toolRegistry.invoke(eq("checkSyntax"), any())).thenReturn("VALID");
        when(toolRegistry.invoke(eq("checkSyntaxWithLsp"), any())).thenReturn("LSP_OK: No errors");
        when(toolRegistry.invoke(eq("compileProject"), any())).thenReturn("BUILD SUCCESS");
        when(toolRegistry.invoke(eq("executeTest"), any())).thenReturn("Failures: 0, Errors: 0");
        when(toolRegistry.invoke(eq("getSingleMethodCoverage"), any())).thenReturn("line=90%");
        
        VerificationResult result = pipeline.execute(
                "src/test/java/Test.java",
                "com.example.Test",
                "com.example.Calculator",
                "add",
                ".");
        
        assertTrue(result.isSuccess());
        verify(toolRegistry).invoke(eq("checkSyntaxWithLsp"), any());
    }
    
    @Test
    void testExecuteLspCheckFails() throws Exception {
        when(workflowConfig.isUseLsp()).thenReturn(true);
        pipeline = new VerificationPipeline(toolRegistry, config);
        
        when(toolRegistry.invoke(eq("checkSyntax"), any())).thenReturn("VALID");
        when(toolRegistry.invoke(eq("checkSyntaxWithLsp"), any()))
                .thenReturn("LSP_ERRORS: Type not found");
        
        VerificationResult result = pipeline.execute(
                "src/test/java/Test.java",
                "com.example.Test",
                "com.example.Calculator",
                "add",
                ".");
        
        assertFalse(result.isSuccess());
        assertEquals(VerificationStep.LSP_CHECK, result.getFailedStep());
    }
    
    @Test
    void testExecuteCoverageBelowThreshold() throws Exception {
        when(toolRegistry.invoke(eq("checkSyntax"), any())).thenReturn("VALID");
        when(toolRegistry.invoke(eq("compileProject"), any())).thenReturn("BUILD SUCCESS");
        when(toolRegistry.invoke(eq("executeTest"), any())).thenReturn("Failures: 0, Errors: 0");
        when(toolRegistry.invoke(eq("getSingleMethodCoverage"), any())).thenReturn("line=50%");
        
        VerificationResult result = pipeline.execute(
                "src/test/java/Test.java",
                "com.example.Test",
                "com.example.Calculator",
                "add",
                ".");
        
        assertTrue(result.isSuccess());
        assertEquals(50.0, result.getCoverage(), 0.1);
        assertFalse(result.isCoverageThresholdMet());
    }
    
    @Test
    void testCheckSyntaxOnly() throws Exception {
        when(toolRegistry.invoke(eq("checkSyntax"), any())).thenReturn("VALID");
        
        VerificationResult result = pipeline.checkSyntaxOnly("test.java");
        
        assertTrue(result.isSuccess());
        verify(toolRegistry).invoke(eq("checkSyntax"), any());
    }
    
    @Test
    void testCheckSyntaxOnlyWithLsp() throws Exception {
        when(workflowConfig.isUseLsp()).thenReturn(true);
        pipeline = new VerificationPipeline(toolRegistry, config);
        
        when(toolRegistry.invoke(eq("checkSyntax"), any())).thenReturn("VALID");
        when(toolRegistry.invoke(eq("checkSyntaxWithLsp"), any())).thenReturn("LSP_OK");
        
        VerificationResult result = pipeline.checkSyntaxOnly("test.java");
        
        assertTrue(result.isSuccess());
        verify(toolRegistry).invoke(eq("checkSyntax"), any());
        verify(toolRegistry).invoke(eq("checkSyntaxWithLsp"), any());
    }
    
    @Test
    void testCompileOnly() throws Exception {
        when(toolRegistry.invoke(eq("compileProject"), any())).thenReturn("BUILD SUCCESS");
        
        VerificationResult result = pipeline.compileOnly();
        
        assertTrue(result.isSuccess());
        verify(toolRegistry).invoke(eq("compileProject"), any());
    }
    
    @Test
    void testTestOnly() throws Exception {
        when(toolRegistry.invoke(eq("executeTest"), any())).thenReturn("Failures: 0, Errors: 0");
        
        VerificationResult result = pipeline.testOnly("com.example.Test");
        
        assertTrue(result.isSuccess());
        verify(toolRegistry).invoke(eq("executeTest"), any());
    }
    
    @Test
    void testExecuteWithToolException() throws Exception {
        when(toolRegistry.invoke(eq("checkSyntax"), any()))
                .thenThrow(new RuntimeException("Tool error"));
        
        VerificationResult result = pipeline.execute(
                "src/test/java/Test.java",
                "com.example.Test",
                "com.example.Calculator",
                "add",
                ".");
        
        assertFalse(result.isSuccess());
        assertEquals(VerificationStep.SYNTAX_CHECK, result.getFailedStep());
        assertTrue(result.getErrorMessage().contains("Tool error"));
    }
    
    @Test
    void testExecuteWithNullToolResult() throws Exception {
        when(toolRegistry.invoke(eq("checkSyntax"), any())).thenReturn(null);
        
        VerificationResult result = pipeline.execute(
                "src/test/java/Test.java",
                "com.example.Test",
                "com.example.Calculator",
                "add",
                ".");
        
        assertFalse(result.isSuccess());
        assertEquals(VerificationStep.SYNTAX_CHECK, result.getFailedStep());
    }
    
    @Test
    void testCoverageParsingWithDifferentFormats() throws Exception {
        when(toolRegistry.invoke(eq("checkSyntax"), any())).thenReturn("VALID");
        when(toolRegistry.invoke(eq("compileProject"), any())).thenReturn("BUILD SUCCESS");
        when(toolRegistry.invoke(eq("executeTest"), any())).thenReturn("Failures: 0");
        
        // 测试不同的覆盖率格式
        when(toolRegistry.invoke(eq("getSingleMethodCoverage"), any())).thenReturn("method line: 75.5%");
        VerificationResult result1 = pipeline.execute("t.java", "T", "C", "m", ".");
        assertEquals(75.5, result1.getCoverage(), 0.1);
        
        // 格式2: 简单百分比
        when(toolRegistry.invoke(eq("getSingleMethodCoverage"), any())).thenReturn("Coverage: 88%");
        VerificationResult result2 = pipeline.execute("t.java", "T", "C", "m", ".");
        assertEquals(88.0, result2.getCoverage(), 0.1);
    }
    
    @Test
    void testLspWarningsDoNotBlockPipeline() throws Exception {
        when(workflowConfig.isUseLsp()).thenReturn(true);
        pipeline = new VerificationPipeline(toolRegistry, config);
        
        when(toolRegistry.invoke(eq("checkSyntax"), any())).thenReturn("VALID");
        when(toolRegistry.invoke(eq("checkSyntaxWithLsp"), any())).thenReturn("LSP_WARNINGS: unused import");
        when(toolRegistry.invoke(eq("compileProject"), any())).thenReturn("BUILD SUCCESS");
        when(toolRegistry.invoke(eq("executeTest"), any())).thenReturn("Failures: 0, Errors: 0");
        when(toolRegistry.invoke(eq("getSingleMethodCoverage"), any())).thenReturn("line=80%");
        
        VerificationResult result = pipeline.execute(
                "src/test/java/Test.java",
                "com.example.Test",
                "com.example.Calculator",
                "add",
                ".");
        
        assertTrue(result.isSuccess());
    }
}
