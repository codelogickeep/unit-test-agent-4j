package com.codelogickeep.agent.ut.framework.phase;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowPhaseTest {

    // ========== ANALYSIS Phase Tests ==========
    
    @Test
    void testAnalysisPhaseToolNames() {
        List<String> tools = WorkflowPhase.ANALYSIS.getToolNames();
        assertEquals(9, tools.size());
        
        // 核心工具
        assertTrue(tools.contains("CodeAnalyzerTool"), "ANALYSIS needs CodeAnalyzerTool for getPriorityMethods");
        assertTrue(tools.contains("FileSystemTool"), "ANALYSIS needs FileSystemTool for readFile/writeFile");
        assertTrue(tools.contains("DirectoryTool"), "ANALYSIS needs DirectoryTool for directoryExists");
        
        // 关键：MethodIteratorTool 用于 initMethodIteration
        assertTrue(tools.contains("MethodIteratorTool"), 
                "ANALYSIS MUST contain MethodIteratorTool for initMethodIteration!");
        
        // 分析工具
        assertTrue(tools.contains("BoundaryAnalyzerTool"));
        assertTrue(tools.contains("TestDiscoveryTool"));
        assertTrue(tools.contains("ProjectScannerTool"));
        
        // PreCheckExecutor 需要的工具
        assertTrue(tools.contains("MavenExecutorTool"), "ANALYSIS needs MavenExecutorTool for cleanAndTest");
        assertTrue(tools.contains("CoverageTool"), "ANALYSIS needs CoverageTool for getMethodCoverageDetails");
    }
    
    @Test
    void testAnalysisPhaseHasMethodIteratorTool() {
        // 专门测试 MethodIteratorTool 存在性，因为这是之前缺失的关键工具
        List<String> tools = WorkflowPhase.ANALYSIS.getToolNames();
        assertTrue(tools.contains("MethodIteratorTool"), 
                "CRITICAL: MethodIteratorTool is required for initMethodIteration and getNextMethod");
    }

    // ========== GENERATION Phase Tests ==========
    
    @Test
    void testGenerationPhaseToolNames() {
        List<String> tools = WorkflowPhase.GENERATION.getToolNames();
        assertEquals(4, tools.size());
        
        // 核心工具
        assertTrue(tools.contains("FileSystemTool"), "GENERATION needs FileSystemTool for writeFile");
        assertTrue(tools.contains("CodeAnalyzerTool"), "GENERATION needs CodeAnalyzerTool to analyze source");
        assertTrue(tools.contains("KnowledgeBaseTool"), "GENERATION needs KnowledgeBaseTool for RAG");
        assertTrue(tools.contains("BoundaryAnalyzerTool"), "GENERATION needs BoundaryAnalyzerTool for edge cases");
    }
    
    @Test
    void testGenerationPhaseDoesNotContainVerificationTools() {
        // 验证工具不应该在生成阶段，因为验证由管道自动执行
        List<String> tools = WorkflowPhase.GENERATION.getToolNames();
        assertFalse(tools.contains("SyntaxCheckerTool"), 
                "GENERATION should NOT contain SyntaxCheckerTool - verification is automatic");
        assertFalse(tools.contains("LspSyntaxCheckerTool"), 
                "GENERATION should NOT contain LspSyntaxCheckerTool - verification is automatic");
        assertFalse(tools.contains("MavenExecutorTool"), 
                "GENERATION should NOT contain MavenExecutorTool - verification is automatic");
    }

    // ========== VERIFICATION Phase Tests ==========
    
    @Test
    void testVerificationPhaseToolNames() {
        List<String> tools = WorkflowPhase.VERIFICATION.getToolNames();
        assertEquals(6, tools.size());
        
        // 验证管道必需的工具
        assertTrue(tools.contains("SyntaxCheckerTool"), "VERIFICATION needs SyntaxCheckerTool");
        assertTrue(tools.contains("LspSyntaxCheckerTool"), "VERIFICATION needs LspSyntaxCheckerTool");
        assertTrue(tools.contains("MavenExecutorTool"), "VERIFICATION needs MavenExecutorTool");
        assertTrue(tools.contains("CoverageTool"), "VERIFICATION needs CoverageTool");
        assertTrue(tools.contains("TestReportTool"), "VERIFICATION needs TestReportTool");
        assertTrue(tools.contains("FileSystemTool"), "VERIFICATION needs FileSystemTool for reports");
    }
    
    @Test
    void testVerificationPhaseHasAllPipelineTools() {
        // 确保验证管道所需的所有工具都存在
        List<String> tools = WorkflowPhase.VERIFICATION.getToolNames();
        String[] pipelineTools = {"SyntaxCheckerTool", "LspSyntaxCheckerTool", "MavenExecutorTool", "CoverageTool"};
        for (String tool : pipelineTools) {
            assertTrue(tools.contains(tool), "VERIFICATION must contain " + tool + " for pipeline");
        }
    }

    // ========== REPAIR Phase Tests ==========
    
    @Test
    void testRepairPhaseToolNames() {
        List<String> tools = WorkflowPhase.REPAIR.getToolNames();
        assertEquals(3, tools.size());
        
        // 修复阶段只需要修改代码的工具
        assertTrue(tools.contains("FileSystemTool"), "REPAIR needs FileSystemTool for searchReplace");
        assertTrue(tools.contains("CodeAnalyzerTool"), "REPAIR needs CodeAnalyzerTool to understand code");
        assertTrue(tools.contains("TestReportTool"), "REPAIR needs TestReportTool to understand failures");
    }
    
    @Test
    void testRepairPhaseDoesNotContainVerificationTools() {
        // 修复后验证由管道执行，不需要验证工具
        List<String> tools = WorkflowPhase.REPAIR.getToolNames();
        assertFalse(tools.contains("SyntaxCheckerTool"), 
                "REPAIR should NOT contain SyntaxCheckerTool - re-verification is automatic");
        assertFalse(tools.contains("MavenExecutorTool"), 
                "REPAIR should NOT contain MavenExecutorTool - re-verification is automatic");
        assertFalse(tools.contains("CoverageTool"), 
                "REPAIR should NOT contain CoverageTool - re-verification is automatic");
    }

    // ========== FULL Phase Tests ==========
    
    @Test
    void testFullPhaseReturnsEmptyList() {
        List<String> tools = WorkflowPhase.FULL.getToolNames();
        assertTrue(tools.isEmpty(), "FULL phase should have empty list - all tools loaded separately");
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
