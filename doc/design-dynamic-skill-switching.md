# 动态 Skill 切换设计文档

**状态**: 待实现  
**日期**: 2026-01-16  
**优先级**: P2

---

## 1. 背景

当前 Skills 配置已定义各工作流阶段的工具集，但尚未实现动态切换功能。  
目前采用"加载所有工具，LLM 自主选择"的简单方案。

### 1.1 当前状态

- Skills 配置存在于 `agent.yml` 中
- 启动时加载所有工具（15+）
- LLM 在 ReAct 循环中自主决定使用哪些工具
- `--skill` 命令行参数已移除（完整流程需要所有阶段工具）

### 1.2 潜在问题

| 问题 | 影响 |
|------|------|
| Token 消耗高 | 每次 LLM 请求都包含所有工具定义 |
| 工具干扰 | 不相关的工具可能导致 LLM 选择错误 |
| 上下文膨胀 | 工具数量多时系统提示词过长 |

---

## 2. 设计目标

实现**动态 Skill 切换**，根据工作流阶段自动加载对应的工具集。

### 2.1 工作流阶段定义

```
┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│   分析      │ → │   生成      │ → │   验证      │ → │   修复      │
│  analysis   │   │ generation  │   │verification │   │   repair    │
└─────────────┘   └─────────────┘   └─────────────┘   └─────────────┘
      │                 │                 │                 │
      ▼                 ▼                 ▼                 ▼
   7 工具            6 工具            7 工具            7 工具
```

### 2.2 阶段检测策略

| 检测方式 | 说明 | 复杂度 |
|----------|------|--------|
| LLM 自报告 | LLM 输出包含阶段标记 | 低 |
| 工具调用模式 | 根据最近调用的工具推断 | 中 |
| 显式阶段指令 | 系统提示词中定义阶段转换规则 | 中 |
| Orchestrator 控制 | 编排器主动切换阶段 | 高 |

---

## 3. Skills 配置参考

以下是各阶段的工具集定义（保留在 `agent.yml` 中）：

### 3.1 analysis - 代码分析阶段

```yaml
- name: "analysis"
  description: "Code analysis phase - read and understand source code"
  tools:
    - CodeAnalyzerTool       # 代码 AST 分析
    - FileSystemTool         # 文件读取
    - DirectoryTool          # 目录操作
    - BoundaryAnalyzerTool   # 边界值分析
    - StyleAnalyzerTool      # 代码风格分析
    - ProjectScannerTool     # 项目扫描
    - TestDiscoveryTool      # 测试发现
```

### 3.2 generation - 测试生成阶段

```yaml
- name: "generation"
  description: "Test generation phase - generate and write test files"
  tools:
    - FileSystemTool         # 文件写入
    - DirectoryTool          # 目录创建
    - KnowledgeBaseTool      # RAG 知识检索
    - SyntaxCheckerTool      # 语法检查
    - LspSyntaxCheckerTool   # LSP 语义检查（可选）
    - CodeAnalyzerTool       # 代码分析（生成时参考）
```

### 3.3 verification - 测试验证阶段

```yaml
- name: "verification"
  description: "Test verification phase - execute tests and check coverage"
  tools:
    - MavenExecutorTool      # 编译和运行测试
    - SyntaxCheckerTool      # CompileGuard 要求
    - LspSyntaxCheckerTool   # LSP 语义检查（可选）
    - TestReportTool         # 测试报告解析
    - CoverageTool           # 覆盖率分析
    - FileSystemTool         # 读取报告文件
    - MutationTestTool       # 变异测试（可选）
```

### 3.4 repair - 修复阶段

```yaml
- name: "repair"
  description: "Repair phase - fix test failures based on error reports"
  tools:
    - FileSystemTool         # 文件修改
    - TestReportTool         # 错误报告分析
    - CodeAnalyzerTool       # 代码分析
    - SyntaxCheckerTool      # CompileGuard 要求
    - LspSyntaxCheckerTool   # LSP 语义检查（可选）
    - MavenExecutorTool      # 重新编译
    - CoverageTool           # 修复后验证覆盖率
```

### 3.5 iterative - 迭代模式

```yaml
- name: "iterative"
  description: "Iterative mode - generate tests method by method with priority"
  tools:
    - CodeAnalyzerTool
    - CoverageTool
    - MethodIteratorTool     # 迭代控制
    - FileSystemTool
    - DirectoryTool
    - SyntaxCheckerTool
    - LspSyntaxCheckerTool
    - MavenExecutorTool
    - TestReportTool
    - KnowledgeBaseTool
    - BoundaryAnalyzerTool
```

### 3.6 incremental - 增量模式

```yaml
- name: "incremental"
  description: "Incremental mode - only test changed files from Git"
  tools:
    - GitDiffTool            # Git 差异分析
    - CodeAnalyzerTool
    - FileSystemTool
    - DirectoryTool
    - SyntaxCheckerTool
    - LspSyntaxCheckerTool
    - MavenExecutorTool
    - CoverageTool
    - TestReportTool
    - KnowledgeBaseTool
```

---

## 4. 实现方案

### 4.1 方案 A: Orchestrator 主动切换（推荐）

在 `SimpleAgentOrchestrator` 中实现阶段管理：

```java
public class SimpleAgentOrchestrator {
    private WorkflowPhase currentPhase = WorkflowPhase.ANALYSIS;
    
    enum WorkflowPhase {
        ANALYSIS, GENERATION, VERIFICATION, REPAIR
    }
    
    public void run(String targetFile, String taskContext) {
        // 阶段 1: 分析
        currentPhase = WorkflowPhase.ANALYSIS;
        ToolRegistry analysisTools = loadToolsForSkill("analysis");
        runPhase(analysisTools, buildAnalysisPrompt(targetFile));
        
        // 阶段 2: 生成
        currentPhase = WorkflowPhase.GENERATION;
        ToolRegistry generationTools = loadToolsForSkill("generation");
        runPhase(generationTools, buildGenerationPrompt(targetFile));
        
        // 阶段 3: 验证
        currentPhase = WorkflowPhase.VERIFICATION;
        ToolRegistry verificationTools = loadToolsForSkill("verification");
        VerificationResult result = runVerification(verificationTools);
        
        // 阶段 4: 修复循环
        while (!result.success && retryCount < maxRetries) {
            currentPhase = WorkflowPhase.REPAIR;
            ToolRegistry repairTools = loadToolsForSkill("repair");
            runPhase(repairTools, buildRepairPrompt(result.errors));
            result = runVerification(verificationTools);
        }
    }
}
```

### 4.2 方案 B: 工具调用模式检测

根据最近调用的工具自动推断当前阶段：

```java
public class PhaseDetector {
    private static final Map<String, WorkflowPhase> TOOL_PHASE_MAP = Map.of(
        "analyzeClass", WorkflowPhase.ANALYSIS,
        "writeFile", WorkflowPhase.GENERATION,
        "compileProject", WorkflowPhase.VERIFICATION,
        "searchReplace", WorkflowPhase.REPAIR
    );
    
    public WorkflowPhase detectPhase(List<String> recentToolCalls) {
        // 根据最近调用的工具推断阶段
        // ...
    }
}
```

### 4.3 Token 节省预估

| 方案 | 工具数 | Token 节省 |
|------|--------|------------|
| 全量加载 | 15+ | 基准 |
| 动态切换 | 6-11 | ~40-60% |

---

## 5. 实施计划

### Phase 1: 基础设施（1天）
- [ ] 在 `ToolFactory` 中添加 `loadToolsForSkill(skillName)` 方法
- [ ] 在 `AgentExecutor` 中支持动态更新 `ToolRegistry`

### Phase 2: Orchestrator 改造（2天）
- [ ] 定义 `WorkflowPhase` 枚举
- [ ] 实现阶段切换逻辑
- [ ] 更新系统提示词（针对每个阶段）

### Phase 3: 测试与优化（1天）
- [ ] 集成测试
- [ ] Token 消耗对比
- [ ] 性能调优

---

## 6. 风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 阶段判断错误 | 工具不可用 | 保留回退到全量加载的选项 |
| 上下文丢失 | 阶段切换时丢失信息 | 维护跨阶段上下文摘要 |
| 复杂度增加 | 维护成本 | 保持简单，先实现方案 A |

---

## 7. 变更日志

| 日期 | 版本 | 变更 |
|------|------|------|
| 2026-01-16 | 1.0 | 初始设计文档 |
