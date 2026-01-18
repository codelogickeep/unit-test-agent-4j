package com.codelogickeep.agent.ut.framework.phase;

import java.util.Arrays;
import java.util.List;

/**
 * 工作流阶段枚举，定义测试生成的不同阶段及其对应的工具集
 * 
 * 设计原则：
 * - 每个阶段只加载 LLM 需要的工具
 * - 验证阶段由 VerificationPipeline 自动执行，工具供管道使用
 * - 修复阶段 LLM 只需修改代码，验证由管道重新执行
 */
public enum WorkflowPhase {
    /**
     * 分析阶段 - 分析源代码结构、初始化迭代、创建测试骨架
     * 
     * 用途：
     * - 读取源文件分析结构 (FileSystemTool, CodeAnalyzerTool)
     * - 检查目录和文件存在性 (DirectoryTool)
     * - 初始化方法迭代 (MethodIteratorTool) ← 关键！
     * - 分析边界条件 (BoundaryAnalyzerTool)
     * - 发现现有测试 (TestDiscoveryTool)
     * - 运行测试获取覆盖率 (MavenExecutorTool, CoverageTool) ← PreCheckExecutor 需要
     */
    ANALYSIS(Arrays.asList(
        "CodeAnalyzerTool",       // getPriorityMethods, analyzeClass
        "FileSystemTool",         // readFile, writeFile
        "DirectoryTool",          // directoryExists, fileExists
        "MethodIteratorTool",     // initMethodIteration, getNextMethod
        "BoundaryAnalyzerTool",   // 边界值分析
        "TestDiscoveryTool",      // 发现现有测试
        "ProjectScannerTool",     // 项目结构扫描
        "MavenExecutorTool",      // cleanAndTest（PreCheckExecutor 需要）
        "CoverageTool"            // getMethodCoverageDetails, getUncoveredMethods
    )),

    /**
     * 生成阶段 - LLM 生成测试代码
     * 
     * 用途：
     * - 读取源文件和现有测试 (FileSystemTool)
     * - 写入新测试代码 (FileSystemTool)
     * - 分析代码逻辑 (CodeAnalyzerTool)
     * - RAG 知识检索 (KnowledgeBaseTool)
     * 
     * 注意：不包含验证工具，验证由 VerificationPipeline 自动执行
     */
    GENERATION(Arrays.asList(
        "FileSystemTool",         // readFile, writeFile, writeFileFromLine
        "CodeAnalyzerTool",       // 分析源代码逻辑
        "KnowledgeBaseTool",      // RAG 检索测试模式
        "BoundaryAnalyzerTool"    // 边界条件建议
    )),

    /**
     * 验证阶段 - 编译、运行测试、检查覆盖率
     * 
     * 用途：由 VerificationPipeline 自动调用
     * - 语法检查 (SyntaxCheckerTool, LspSyntaxCheckerTool)
     * - 编译测试 (MavenExecutorTool)
     * - 执行测试 (MavenExecutorTool)
     * - 获取覆盖率 (CoverageTool)
     */
    VERIFICATION(Arrays.asList(
        "SyntaxCheckerTool",      // checkSyntax
        "LspSyntaxCheckerTool",   // checkSyntaxWithLsp
        "MavenExecutorTool",      // compileProject, executeTest
        "CoverageTool",           // getSingleMethodCoverage
        "TestReportTool",         // 解析测试报告（可选）
        "FileSystemTool"          // 读取报告文件
    )),

    /**
     * 修复阶段 - LLM 修复测试失败
     * 
     * 用途：
     * - 读取测试文件和源文件 (FileSystemTool)
     * - 修改代码修复错误 (FileSystemTool)
     * - 分析代码结构 (CodeAnalyzerTool)
     * - 理解测试失败原因 (TestReportTool)
     * 
     * 注意：不包含验证工具，修复后由管道重新验证
     */
    REPAIR(Arrays.asList(
        "FileSystemTool",         // readFile, searchReplace, writeFile
        "CodeAnalyzerTool",       // 分析代码帮助理解问题
        "TestReportTool"          // 理解测试失败的详细原因
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
