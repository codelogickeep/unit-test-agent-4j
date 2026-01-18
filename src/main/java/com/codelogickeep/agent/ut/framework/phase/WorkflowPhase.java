package com.codelogickeep.agent.ut.framework.phase;

import java.util.Arrays;
import java.util.List;

/**
 * 工作流阶段枚举，定义测试生成的不同阶段及其对应的工具集
 */
public enum WorkflowPhase {
    /**
     * 分析阶段 - 分析源代码结构、边界条件、现有测试风格
     */
    ANALYSIS(Arrays.asList(
        "CodeAnalyzerTool",
        "FileSystemTool",
        "DirectoryTool",
        "BoundaryAnalyzerTool",
        "StyleAnalyzerTool",
        "ProjectScannerTool",
        "TestDiscoveryTool"
    )),

    /**
     * 生成阶段 - 生成测试代码
     */
    GENERATION(Arrays.asList(
        "FileSystemTool",
        "DirectoryTool",
        "KnowledgeBaseTool",
        "SyntaxCheckerTool",
        "LspSyntaxCheckerTool",
        "CodeAnalyzerTool"
    )),

    /**
     * 验证阶段 - 编译、运行测试、检查覆盖率
     */
    VERIFICATION(Arrays.asList(
        "MavenExecutorTool",
        "SyntaxCheckerTool",
        "LspSyntaxCheckerTool",
        "TestReportTool",
        "CoverageTool",
        "FileSystemTool",
        "MutationTestTool"
    )),

    /**
     * 修复阶段 - 根据测试失败原因修复测试代码
     */
    REPAIR(Arrays.asList(
        "FileSystemTool",
        "TestReportTool",
        "CodeAnalyzerTool",
        "SyntaxCheckerTool",
        "LspSyntaxCheckerTool",
        "MavenExecutorTool",
        "CoverageTool"
    )),

    /**
     * 完整模式 - 加载所有工具（向后兼容）
     */
    FULL(Arrays.asList());

    private final List<String> toolNames;

    WorkflowPhase(List<String> toolNames) {
        this.toolNames = toolNames;
    }

    /**
     * 获取该阶段的工具类名列表
     */
    public List<String> getToolNames() {
        return toolNames;
    }

    /**
     * 获取下一个阶段
     */
    public WorkflowPhase next() {
        switch (this) {
            case ANALYSIS:
                return GENERATION;
            case GENERATION:
                return VERIFICATION;
            case VERIFICATION:
                return REPAIR;
            case REPAIR:
            case FULL:
            default:
                return this;
        }
    }

    /**
     * 根据 LLM 响应判断是否需要切换到修复阶段
     */
    public static boolean shouldSwitchToRepair(String llmResponse) {
        if (llmResponse == null) {
            return false;
        }
        String lower = llmResponse.toLowerCase();
        return lower.contains("compilation error") ||
               lower.contains("test failed") ||
               lower.contains("assertion failed") ||
               lower.contains("编译错误") ||
               lower.contains("测试失败") ||
               lower.contains("断言失败");
    }
}
