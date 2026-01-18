package com.codelogickeep.agent.ut.framework.phase;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowPhaseTest {

    @Test
    void testAnalysisPhaseToolNames() {
        List<String> tools = WorkflowPhase.ANALYSIS.getToolNames();
        assertEquals(7, tools.size());
        assertTrue(tools.contains("CodeAnalyzerTool"));
        assertTrue(tools.contains("FileSystemTool"));
        assertTrue(tools.contains("BoundaryAnalyzerTool"));
    }

    @Test
    void testGenerationPhaseToolNames() {
        List<String> tools = WorkflowPhase.GENERATION.getToolNames();
        assertEquals(6, tools.size());
        assertTrue(tools.contains("FileSystemTool"));
        assertTrue(tools.contains("KnowledgeBaseTool"));
        assertTrue(tools.contains("SyntaxCheckerTool"));
    }

    @Test
    void testVerificationPhaseToolNames() {
        List<String> tools = WorkflowPhase.VERIFICATION.getToolNames();
        assertEquals(7, tools.size());
        assertTrue(tools.contains("MavenExecutorTool"));
        assertTrue(tools.contains("CoverageTool"));
        assertTrue(tools.contains("TestReportTool"));
    }

    @Test
    void testRepairPhaseToolNames() {
        List<String> tools = WorkflowPhase.REPAIR.getToolNames();
        assertEquals(7, tools.size());
        assertTrue(tools.contains("FileSystemTool"));
        assertTrue(tools.contains("SyntaxCheckerTool"));
        assertTrue(tools.contains("MavenExecutorTool"));
    }

    @Test
    void testFullPhaseReturnsEmptyList() {
        List<String> tools = WorkflowPhase.FULL.getToolNames();
        assertTrue(tools.isEmpty());
    }

    @Test
    void testPhaseTransition() {
        assertEquals(WorkflowPhase.GENERATION, WorkflowPhase.ANALYSIS.next());
        assertEquals(WorkflowPhase.VERIFICATION, WorkflowPhase.GENERATION.next());
        assertEquals(WorkflowPhase.REPAIR, WorkflowPhase.VERIFICATION.next());
        assertEquals(WorkflowPhase.REPAIR, WorkflowPhase.REPAIR.next());
    }

    @Test
    void testFullPhaseNextReturnsItself() {
        assertEquals(WorkflowPhase.FULL, WorkflowPhase.FULL.next());
    }

    @Test
    void testShouldSwitchToRepair() {
        assertTrue(WorkflowPhase.shouldSwitchToRepair("compilation error occurred"));
        assertTrue(WorkflowPhase.shouldSwitchToRepair("test failed with exception"));
        assertTrue(WorkflowPhase.shouldSwitchToRepair("assertion failed"));
        assertTrue(WorkflowPhase.shouldSwitchToRepair("编译错误"));
        assertTrue(WorkflowPhase.shouldSwitchToRepair("测试失败"));
        assertFalse(WorkflowPhase.shouldSwitchToRepair("all tests passed"));
        assertFalse(WorkflowPhase.shouldSwitchToRepair(null));
    }
}
