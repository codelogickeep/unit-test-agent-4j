# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 语言偏好

**使用中文（简体中文）与用户进行所有交流。** 代码注释和技术文档可以保留英文。

## 项目概述

Unit Test Agent 4j 是一个 AI 驱动的 Java 单元测试自动生成工具，专注于为遗留系统生成 JUnit 5 + Mockito 测试代码。它使用自研的轻量级 Agent 框架集成多个 LLM 提供商（OpenAI、Anthropic、Gemini、智谱 AI），并包含自修复能力。

**当前版本**: 2.1.1

### 最近修复（v2.1.1）

- **NPE 修复**: 修复 SimpleAgentOrchestrator 中类提取的空指针异常，使用 JavaParser 进行健壮的类解析
- **LSP 线程同步**: 修复 LspSyntaxCheckerTool 中的线程同步问题，简化语法检查器
- **迭代终止逻辑**: 改进覆盖率检查和信号检测的健壮性
- **项目根检测**: 改进项目根目录检测逻辑，支持相对路径
- **语法检查增强**: 添加依赖检查和改进 LSP 诊断

### 核心特性

| 特性类别 | 具体能力 |
|---------|---------|
| **自研 Agent 框架** | 轻量级专用框架（v2.0.0 起替代 LangChain4j），JAR 体积减小约 50% |
| **多模型支持** | OpenAI、Anthropic (Claude)、Gemini、智谱 AI、OpenAI 兼容代理 |
| **智能环境审计** | 自动检测项目依赖版本兼容性（JUnit 5、Mockito、JaCoCo） |
| **自修复机制** | 自动编译运行测试，基于 Surefire 报告分类修复 |
| **编译守卫机制** | 工具层面强制约束：语法检查必须通过才能编译（v2.1.0） |
| **项目规范学习** | StyleAnalyzer 提取现有测试风格，RAG 检索文档模板 |
| **知识库索引持久化** | 缓存索引到 `.utagent/kb-cache.json`，避免重复构建（v2.1.0） |
| **Git 增量检测** | 支持未提交/暂存区/任意 Ref 比较的增量分析 |
| **变更影响分析** | 构建类依赖图，识别代码变更影响的测试（v2.1.0） |
| **测试质量评估** | PITest 变异测试 + 边界值分析 + 覆盖率反馈循环 |
| **智能停滞检测** | 覆盖率停止增长时智能停止迭代（v2.1.0） |
| **LSP 语法检查** | 可选 Eclipse JDT Language Server 语义分析 |
| **预编译验证** | JavaParser 快速语法检查（~10ms） |

## 构建和运行命令

```bash
# 构建项目（创建 shaded JAR）
mvn clean package

# 运行本项目的测试
mvn test

# 运行特定测试类
mvn test -Dtest=ClassName

# 构建原生镜像（需要 GraalVM）
mvn -Pnative package

# 运行 agent（单文件模式）
java -jar target/utagent.jar --target path/to/Class.java

# 批量模式（扫描整个项目）
java -jar target/utagent.jar --project /path/to/project --exclude "**/dto/**"

# 增量模式（Git 未提交变更）
java -jar target/utagent.jar --project /path/to/project --incremental

# 增量模式（比较 refs）
java -jar target/utagent.jar --project /path/to/project \
  --incremental --incremental-mode COMPARE_REFS --base-ref main --target-ref HEAD

# 配置 LLM 设置（保存到 agent.yml）
java -jar target/utagent.jar config --protocol openai --api-key "sk-..." --model "gpt-4"

# 检查环境（Maven、依赖、权限）
java -jar target/utagent.jar --check-env

# 交互模式（文件写入前确认）
java -jar target/utagent.jar --target path/to/Class.java --interactive
```

## 架构设计

项目采用 **自研 Agent-Tool 架构**（v2.0.0 起替代 LangChain4j）：

### 入口点 (`App.java`)
- 基于 Picocli 的 CLI，支持 `--target`（单文件）、`--project`（批量模式）、`--incremental`（增量模式）选项
- 包含嵌套的 `ConfigCommand` 用于持久化配置

### 自研 Agent 框架 (`framework/`)
**v2.0.0 核心变更**：完全移除 LangChain4j 依赖，使用自研轻量级框架

- **`adapter/`**: LLM 适配器层
  - `OpenAiAdapter`: OpenAI 和兼容 API（包括智谱 AI）
  - `ClaudeAdapter`: Anthropic Claude API
  - `GeminiAdapter`: Google Gemini API
  - 统一的流式响应处理

- **`context/`**: 上下文管理
  - `ContextManager`: 智能消息裁剪，保持有效对话序列
  - 支持消息历史管理和 token 优化

- **`executor/`**: Agent 执行器
  - `AgentExecutor`: ReAct 循环执行器
  - 支持工具调用和流式响应

- **`tool/`**: 工具注册与执行
  - `ToolRegistry`: 反射式工具发现和注册
  - `JsonUtil`: 统一的 JSON 序列化/反序列化

- **`model/`**: 数据模型
  - `Message`: 统一的消息模型
  - `ToolCall`: 工具调用模型
  - `IterationStats`: 迭代统计

### Agent 层 (`engine/`)
处理推理、编排和 LLM 交互

- `SimpleAgentOrchestrator`: 主工作流，包含重试循环和指数退避
- `RetryExecutor`: 重试执行器，独立处理指数退避逻辑
- `StreamingResponseHandler`: 实时流式响应处理器
- `RepairTracker`: 修复轨迹记录，防止死循环
- `DynamicPromptBuilder`: 动态 Prompt 组装器，根据项目规范调整系统提示
- `EnvironmentChecker`: 验证项目依赖和版本
- `BatchAnalyzer`: 批量模式预分析
- `IncrementalAnalyzer`: Git 增量分析器，整合 Git 差异与测试任务生成
- `CoverageFeedbackEngine`: 覆盖率反馈引擎，智能分析和改进建议

### 异常层 (`exception/`)
统一异常处理

- `AgentToolException`: 包含 ErrorCode + Context 的统一异常类
- 支持 Builder 模式，提供 `toAgentMessage()` 生成友好错误提示

### 基础设施/工具层 (`tools/`)
Agent 调用的可执行工具（所有工具实现 `AgentTool` 标记接口）

**文件操作**:
- `FileSystemTool`: 文件 I/O，带项目根保护和交互确认模式
- `DirectoryTool`: 目录创建操作

**代码分析**:
- `CodeAnalyzerTool`: 通过 JavaParser 进行 AST 解析
- `SyntaxCheckerTool`: JavaParser 快速语法检查（~10ms）
- `LspSyntaxCheckerTool`: Eclipse JDT Language Server 语义分析（可选）
- `StyleAnalyzerTool`: 代码风格自动提取（Mock 偏好、断言风格、命名惯例）
- `BoundaryAnalyzerTool`: AST 边界条件分析（if/switch/for/while/null）

**构建与测试**:
- `CompileGuard`: 编译守卫，强制语法检查通过才能编译（v2.1.0）
- `MavenExecutorTool`: 运行 `mvn` 命令（支持 Windows PowerShell 7）
- `TestReportTool`: Surefire 报告解析，区分错误类型（编译/断言/超时/依赖）
- `CoverageTool`: JaCoCo XML 报告解析
- `MutationTestTool`: PITest 执行与变异分数报告解析

**版本控制**:
- `GitDiffTool`: 灵活的 Git 差异分析工具（支持未提交/暂存区/任意 Ref 比较）

**项目扫描**:
- `ProjectScannerTool`: 发现 Java 源文件，支持排除模式
- `TestDiscoveryTool`: 查找现有测试文件

**知识库与反馈**:
- `KnowledgeBaseTool`: RAG 风格搜索现有测试模式和 Markdown 文档（支持索引持久化，v2.1.0）
- `CoverageFeedbackEngine`: 多轮覆盖率提升引擎
- `BoundaryAnalyzerTool`: AST 边界条件分析

## 关键设计模式

1. **工具注册**: `ToolRegistry.registerTools()` 通过反射动态发现所有 `AgentTool` 实现并注册

2. **配置分层**: `agent.yml` 从多个位置加载（classpath → 用户主目录 → 当前目录 → JAR 目录 → CLI 路径），后面的源覆盖前面的。支持通过 `${env:VAR_NAME}` 语法使用环境变量。

3. **项目根保护**: `FileSystemTool` 强制所有文件操作相对于检测到的项目根（包含 `pom.xml` 的目录）。这防止了路径遍历问题。

4. **Retry-Streaming-Tracking 三分离架构**: AgentOrchestrator 重构后职责更清晰
   - `RetryExecutor`: 独立的指数退避重试逻辑（2^attempt * 1000ms）
   - `StreamingResponseHandler`: 实时输出 LLM 响应，提升用户体验
   - `RepairTracker`: 跟踪修复历史，防止无效循环

5. **统一异常处理**: `AgentToolException` 提供结构化错误信息
   - `ErrorCode` 枚举: FILE_NOT_FOUND、PARSE_ERROR、EXTERNAL_TOOL_ERROR 等
   - Builder 模式构建异常
   - `toAgentMessage()` 生成面向 LLM 的友好提示

6. **自修复增强**: 从简单重试进化为有目的的修复
   - `TestReportTool` 解析 Surefire XML，区分错误类型
   - 编译错: 检查导包、泛型、Mock 类型
   - 断言失败: 分析预期值与实际值差异
   - 环境/依赖错: 建议或修改 pom.xml

7. **动态 Prompt 组装**: `DynamicPromptBuilder` 根据项目规范调整系统提示
   - 注入项目的测试风格（断言风格、命名规范）
   - 添加边界值测试建议
   - 结合变异测试结果指导增强

8. **增量分析模式**: `IncrementalAnalyzer` 整合 Git 差异
   - 支持三种模式: UNCOMMITTED / STAGED_ONLY / COMPARE_REFS
   - 自动识别变更文件，生成针对性测试任务
   - 变更影响分析: 构建类依赖图，识别受影响的类和测试（v2.1.0）

9. **质量反馈循环**: `CoverageFeedbackEngine` 多轮迭代提升
   - 结合边界分析和变异测试结果
   - 智能决策: 修改现有测试 vs 新增测试 vs 边界值增强
   - 防止无效循环的迭代历史跟踪
   - 智能停滞检测: 5 种停止原因（达标、停滞、无改进、超限、错误）（v2.1.0）

10. **编译守卫机制** (v2.1.0): `CompileGuard` 工具层面约束
    - 写入 Java 文件后自动标记需要语法检查
    - 语法检查通过才允许编译
    - 三重检查: 括号平衡 → JavaParser → LSP（可选）
    - executeTest/cleanAndTest 也受守卫保护

11. **知识库索引持久化** (v2.1.0): 避免重复构建
    - 索引缓存到 `.utagent/kb-cache.json`
    - 文件修改时间 + MD5 哈希验证
    - 支持 `clearCache()`、`rebuild()` 方法

## 配置

默认配置在 `src/main/resources/agent.yml`。运行时配置从以下位置加载（后面的源覆盖前面的）：
1. Classpath（JAR 中的 agent.yml）
2. `~/.unit-test-agent/config.yml` 或 `~/.unit-test-agent/agent.yml`
3. `./config.yml` 或 `./agent.yml`（当前目录）
4. JAR 目录/agent.yml（推荐用于分发）
5. CLI `--config` 路径（最高优先级）

CLI 参数覆盖配置文件值，但除非使用 `--save` 否则不会持久化。

### 新增配置项

```yaml
# 工作流配置
workflow:
  # 交互模式 - 每次文件写入前确认
  interactive: false

  # 启用 LSP 语法检查（自动下载 JDT LS 1.50.0）
  use-lsp: false

  # 启用变异测试（需要 pom.xml 配置 PITest）
  enableMutationTesting: false

  # 最大反馈循环迭代次数
  maxFeedbackLoopIterations: 3

  # 最大停滞迭代次数（v2.1.0）
  max-stale-iterations: 2

  # 最小覆盖率增益（v2.1.0）
  min-coverage-gain: 1.0

# 增量模式配置
incremental:
  # 模式: UNCOMMITTED | STAGED_ONLY | COMPARE_REFS
  mode: UNCOMMITTED

  # COMPARE_REFS 模式下的 Git refs
  # baseRef: "main"
  # targetRef: "HEAD"

  # 文件路径排除模式
  # excludePatterns: ".*Test\\.java"

# 知识库配置（v2.1.0）
knowledge-base:
  # 启用索引持久化
  enable-cache: true

  # 缓存文件路径
  cache-file: ".utagent/kb-cache.json"
```

### LLM 协议

`llm.protocol` 配置支持：
- `openai` - 标准 OpenAI 兼容 API（自动追加 `/v1` 到 baseUrl）
- `openai-zhipu` - 智谱 AI 变体（使用 baseUrl 原样，不追加 `/v1`）
- `anthropic` - Anthropic Claude API
- `gemini` - Google Gemini API（自动追加 `/v1beta` 到 baseUrl）

## 系统提示词

系统提示词模板位于 `src/main/resources/prompts/system-prompt.st`。这包含给 LLM 的核心指令，包括：
- 工作流步骤（分析 → RAG → 规划 → 生成 → 验证）
- JUnit 5 + Mockito 标准（`@ExtendWith(MockitoExtension.class)`）
- 覆盖率驱动增强规则
- 工具使用指南

## 测试依赖

项目需要特定的最低版本用于测试生成（在 `agent.yml` 中定义）：
- JUnit Jupiter: 5.10.1+
- Mockito Core: 5.8.0+
- mockito-inline: 5.8.0+（用于静态 mocking）
- JaCoCo Maven Plugin: 0.8.11+（用于 Java 21+ 兼容性）

## 平台兼容性

- **Windows**: 如果可用，使用 PowerShell 7（`pwsh`）；处理 Windows 路径编码
- **Linux/macOS**: 使用标准 `sh` 和 `mvn`
- Maven 命令通过 `MavenExecutorTool` 执行，处理平台差异

## 模型类

`model/` 中的关键领域模型：
- `Context`: 携带项目根、源/测试路径
- `TestTask`: 表示需要测试的类（批量模式）
- `UncoveredMethod`: 覆盖率低于阈值的方法

## 项目结构说明

- 本项目（`unit-test-agent-4j`）自身没有 `src/test/` 目录 - 它是一个为其他项目生成测试的工具。
- 主类: `com.codelogickeep.agent.ut.App`（在 maven-shade-plugin 中配置）
- 输出 JAR: `target/utagent.jar`

## 添加新工具

要添加 Agent 使用的新工具：
1. 在 `tools/` 中创建一个实现 `AgentTool` 标记接口的类
2. 使用 `@Tool` 注解方法以暴露给 LLM
3. 使用 `@P` 进行参数描述
4. 工具将通过 `ToolRegistry.registerTools()` 通过反射自动发现
5. 对于初始化需求，在 ToolRegistry 中检查 instanceof（如 `KnowledgeBaseTool`）

## 开发阶段总览

| 阶段 | 名称 | 状态 | 优先级 |
|------|------|------|--------|
| Phase 1 | 技术债务清理 | ✅ 已完成 | 最高 |
| Phase 2 | 测试模板与项目规范学习 | ✅ 已完成 | 高 |
| Phase 3 | 测试执行结果分析与自动修复增强 | ✅ 已完成 | 高 |
| Phase 4 | Git 增量检测 | ✅ 已完成 | 中 |
| Phase 5 | 测试质量评估与反馈循环 | ✅ 已完成 | 中 |
| Phase 6 | 工程优化迭代 | ✅ 已完成 | 中 |

### Phase 1: 技术债务清理

- 核心工具单元测试（FileSystemTool、DirectoryTool、CodeAnalyzerTool、CoverageTool、MavenExecutorTool、ProjectScannerTool、TestDiscoveryTool、KnowledgeBaseTool）
- 统一异常处理 `AgentToolException`（ErrorCode + Context + Builder 模式）
- 配置验证与默认值增强（ConfigValidator）
- 日志分级精细化（INFO/DEBUG，-Dut.agent.log.level=DEBUG）
- 项目结构解耦（RetryExecutor + StreamingResponseHandler）

### Phase 2: 测试模板与项目规范学习

- `StyleAnalyzerTool` - 分析现有测试代码风格（Mock 偏好、断言风格、命名惯例）
- RAG 知识库增强 - 支持检索 Markdown 文档（CONTRIBUTING.md、TestingGuide.md）
- 动态 Prompt 组装（`DynamicPromptBuilder`）- 根据项目规范调整系统提示

### Phase 3: 测试执行结果分析与自动修复增强

- `TestReportTool` - 解析 surefire-reports/*.xml，区分错误类型
- 错误分类修复策略 - 编译错/断言失败/环境依赖错
- `RepairTracker` - 记录修复过程，避免死循环

### Phase 4: Git 增量检测

- JGit 集成（org.eclipse.jgit 7.1.0）
- `GitDiffTool` - 灵活的 Git 差异分析工具
- 支持三种模式: 未提交变更/暂存区/任意 Ref 比较
- `IncrementalAnalyzer` - 整合 Git 差异与测试任务生成

### Phase 5: 测试质量评估与反馈循环

- `MutationTestTool` - PITest 执行与变异分数报告解析
- `BoundaryAnalyzerTool` - AST 边界条件分析（if/switch/for/while/null）
- `CoverageFeedbackEngine` - 多轮覆盖率提升引擎

### Phase 6: 工程优化迭代（v2.1.0）

- 核心框架单元测试 - ContextManager、ToolRegistry、JsonUtil 全面测试
- 知识库索引持久化 - 缓存索引，避免每次启动重建
- 变更影响范围分析 - 分析依赖图，识别代码变更影响的测试
- 智能停滞检测 - 覆盖率停止增长时智能停止迭代
- 编译守卫机制 - 工具层面强制约束：语法检查必须通过才能编译
- 新配置项 - `max-stale-iterations`、`min-coverage-gain` 提供精细控制

## 日志级别

| 级别 | 内容 |
|------|------|
| `INFO` | 用户进度、关键里程碑 |
| `DEBUG` | 工具输入输出、技术细节 |
| `WARN` | 非致命问题 |
| `ERROR` | 需要关注的失败 |

启用详细日志:
```bash
# 通过命令行
java -jar utagent.jar --target Foo.java -v

# 通过系统属性
java -Dut.agent.log.level=DEBUG -jar utagent.jar --target Foo.java
```

## 语法检查工具

### JavaParser 快速检查（SyntaxCheckerTool）

- **速度**: ~10ms
- **功能**: 基本语法验证
- **方法**: `checkSyntax()`、`checkSyntaxContent()`、`validateTestStructure()`

### LSP 语义检查（LspSyntaxCheckerTool）

- **速度**: 较慢（~100-500ms）
- **功能**: 完整语义分析（类型错误、缺失导入）
- **配置**: `workflow.use-lsp: true`
- **方法**: `initializeLsp()`、`checkSyntaxWithLsp()`、`shutdownLsp()`
- **自动下载**: JDT Language Server 1.50.0
- **自动初始化**: 当 `use-lsp: true` 时，在程序启动时自动初始化 LSP 服务（无需 LLM 手动调用）
- **强制使用**: DynamicPromptBuilder 会自动在 System Prompt 中添加强制使用 LSP 的指令
- **启动方式**: 直接使用 Java 命令启动 JDT LS（绕过 Python 脚本，更稳定）
- **错误回退**: LSP 初始化失败时自动回退到 JavaParser，不影响主流程

## 交互模式

启用后，每次文件写入前会显示确认界面:

```
╔══════════════════════════════════════════════════════════════════╗
║ WRITE FILE: src/test/java/com/example/MyServiceTest.java         ║
║ Operation: CREATE NEW FILE                                       ║
╟──────────────────────────────────────────────────────────────────╢
║ Preview (first 30 lines):                                        ║
║                                                                  ║
║ package com.example;                                             ║
║                                                                  ║
║ import org.junit.jupiter.api.Test;                               ║
║ import org.junit.jupiter.api.extension.ExtendWith;               ║
║ ...                                                              ║
╟──────────────────────────────────────────────────────────────────╢
║ [Y] Confirm  [n] Cancel  [v] View full content                   ║
╚══════════════════════════════════════════════════════════════════╝
```

## 相关文档

- `doc/plan.md` - 开发路线图（Phase 1-6）
- `doc/overall-design_zh.md` - 整体设计文档（中文版）
- `doc/design-custom-agent-framework.md` - 自研框架设计文档
- `README.md` - 用户文档和使用指南
- `src/main/resources/prompts/system-prompt.st` - LLM 系统提示模板
