package com.codelogickeep.agent.ut.framework.precheck;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import com.codelogickeep.agent.ut.model.MethodCoverageInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoverageAnalyzerTest {

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private AppConfig config;

    @Mock
    private AppConfig.WorkflowConfig workflowConfig;

    private CoverageAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(config.getWorkflow()).thenReturn(workflowConfig);
        when(workflowConfig.getCoverageThreshold()).thenReturn(80);
        analyzer = new CoverageAnalyzer(toolRegistry, config);
    }

    @Test
    void testAnalyzeWithValidCoverage() throws Exception {
        String coverageInfo = "✓ method1() Line: 85.5% Branch: 90.0%\n" +
                              "✗ method2() Line: 50.0% Branch: 60.0%\n" +
                              "✗ method3() Line: 0.0% Branch: 0.0%";

        when(toolRegistry.invoke(eq("getMethodCoverageDetails"), any())).thenReturn(coverageInfo);
        when(toolRegistry.invoke(eq("getUncoveredMethods"), any())).thenReturn("Uncovered methods info");

        CoverageAnalyzer.CoverageResult result = analyzer.analyze("/project", "/project/src/main/java/Test.java");

        assertNotNull(result);
        assertNotNull(result.getCoverageInfo());
        assertTrue(result.getCoverageInfo().contains("method1"));

        List<MethodCoverageInfo> methods = result.getMethodCoverages();
        assertEquals(3, methods.size());

        assertEquals("method1()", methods.get(0).getMethodName());
        assertEquals("P2", methods.get(0).getPriority());
        assertEquals(85.5, methods.get(0).getLineCoverage());

        assertEquals("method2()", methods.get(1).getMethodName());
        assertEquals("P1", methods.get(1).getPriority());

        assertEquals("method3()", methods.get(2).getMethodName());
        assertEquals("P0", methods.get(2).getPriority());
    }

    @Test
    void testAnalyzeWithErrorFallbackToStaticAnalysis() throws Exception {
        String analysisResult = "Method: testMethod1 ()\nMethod: testMethod2 ()";

        when(toolRegistry.invoke(eq("getMethodCoverageDetails"), any())).thenReturn("ERROR: No coverage");
        when(toolRegistry.invoke(eq("analyzeClass"), any())).thenReturn(analysisResult);

        CoverageAnalyzer.CoverageResult result = analyzer.analyze("/project", "/project/src/main/java/Test.java");

        assertNotNull(result);
        List<MethodCoverageInfo> methods = result.getMethodCoverages();
        assertEquals(2, methods.size());
        assertEquals("testMethod1", methods.get(0).getMethodName());
        assertEquals("P0", methods.get(0).getPriority());
        assertEquals(0.0, methods.get(0).getLineCoverage());
    }

    @Test
    void testAnalyzeWithException() throws Exception {
        when(toolRegistry.invoke(anyString(), any())).thenThrow(new RuntimeException("Tool error"));

        CoverageAnalyzer.CoverageResult result = analyzer.analyze("/project", "/project/src/main/java/Test.java");

        assertNotNull(result);
        assertNull(result.getCoverageInfo());
        assertTrue(result.getMethodCoverages().isEmpty());
    }

    @Test
    void testCoverageResultGetters() {
        List<MethodCoverageInfo> methods = List.of(
            new MethodCoverageInfo("method1", "P0", 0.0, 0.0)
        );

        CoverageAnalyzer.CoverageResult result = new CoverageAnalyzer.CoverageResult("coverage info", methods);

        assertEquals("coverage info", result.getCoverageInfo());
        assertEquals(1, result.getMethodCoverages().size());
    }
}
