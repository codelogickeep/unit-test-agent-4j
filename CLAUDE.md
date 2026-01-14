# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language Preference

**Use Chinese (中文) for all communication with the user.** Code comments and technical documentation may remain in English.

## Project Overview

Unit Test Agent 4j is an AI-powered Java tool that automatically generates JUnit 5 + Mockito unit tests for legacy systems. It uses LangChain4j to integrate with multiple LLM providers (OpenAI, Anthropic, Gemini) and includes self-healing capabilities.

**版本**: 1.0.0 (Phase 1-5 已完成)

### 核心特性

| 特性类别 | 具体能力 |
|---------|---------|
| **多模型支持** | OpenAI, Anthropic (Claude), Gemini, OpenAI兼容代理 |
| **智能环境审计** | 自动检测项目依赖版本兼容性 (JUnit 5, Mockito, JaCoCo) |
| **自修复机制** | 自动编译运行测试，基于 Surefire 报告分类修复 |
| **项目规范学习** | StyleAnalyzer 提取现有测试风格，RAG 检索文档模板 |
| **Git 增量检测** | 支持未提交/暂存区/任意 Ref 比较的增量分析 |
| **测试质量评估** | PITest 变异测试 + 边界值分析 + 覆盖率反馈循环 |
| **LSP 语法检查** | 可选 Eclipse JDT Language Server 语义分析 |
| **预编译验证** | JavaParser 快速语法检查 (~10ms) |

## Build & Run Commands

```bash
# Build the project (creates shaded JAR)
mvn clean package

# Run tests for this project
mvn test

# Run a specific test class
mvn test -Dtest=ClassName

# Build native image (requires GraalVM)
mvn -Pnative package

# Run the agent (single file mode)
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar --target path/to/Class.java

# Run in batch mode (scan entire project)
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar --project /path/to/project --exclude "**/dto/**"

# Run in incremental mode (Git uncommitted changes)
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar --project /path/to/project --incremental

# Run in incremental mode (compare refs)
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar --project /path/to/project \
#   --incremental --incremental-mode COMPARE_REFS --base-ref main --target-ref HEAD

# Configure LLM settings (saved to agent.yml)
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar config --protocol openai --api-key "sk-..." --model "gpt-4"

# Check environment (Maven, dependencies, permissions)
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar --check-env

# Interactive mode (confirm before file write)
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar --target path/to/Class.java --interactive
```

## Architecture

The project follows an **Agent-Tool architecture**:

- **Entry Point** (`App.java`): Picocli-based CLI with `--target` (single file), `--project` (batch mode), and `--incremental` (Git incremental mode) options. Contains nested `ConfigCommand` for persistent configuration.

- **Agent Layer** (`engine/`): Handles reasoning, orchestration, and LLM interaction via LangChain4j
  - `AgentOrchestrator`: Main workflow with retry loop and exponential backoff
  - `RetryExecutor`: 重试执行器，独立处理指数退避逻辑
  - `StreamingResponseHandler`: 实时流式响应处理器
  - `RepairTracker`: 修复轨迹记录，防止死循环
  - `DynamicPromptBuilder`: 动态 Prompt 组装器，根据项目规范调整系统提示
  - `LlmClient`: Factory for creating streaming chat models (supports openai, openai-zhipu, anthropic, gemini)
  - `EnvironmentChecker`: Validates project dependencies and versions
  - `BatchAnalyzer`: Pre-analyzes projects for coverage-driven testing
  - `IncrementalAnalyzer`: Git 增量分析器，整合 Git 差异与测试任务生成

- **Exception Layer** (`exception/`): 统一异常处理
  - `AgentToolException`: 包含 ErrorCode + Context 的统一异常类
  - 支持 Builder 模式，提供 toAgentMessage() 生成友好错误提示

- **Infrastructure/Tool Layer** (`tools/`): Executable tools that the Agent calls
  - All tools implement `AgentTool` marker interface (required for auto-discovery via reflection)

  **文件操作**:
  - `FileSystemTool`: File I/O with project root protection and interactive confirmation mode
  - `DirectoryTool`: 目录创建操作

  **代码分析**:
  - `CodeAnalyzerTool`: AST parsing via JavaParser
  - `SyntaxCheckerTool`: JavaParser 快速语法检查 (~10ms)
  - `LspSyntaxCheckerTool`: Eclipse JDT Language Server 语义分析 (可选)
  - `StyleAnalyzerTool`: 代码风格自动提取 (Mock 偏好、断言风格、命名惯例)

  **构建与测试**:
  - `MavenExecutorTool`: Runs `mvn` commands (supports PowerShell 7 on Windows)
  - `TestReportTool`: Surefire 报告解析，区分错误类型 (编译/断言/超时/依赖)
  - `CoverageTool`: JaCoCo XML report parsing
  - `MutationTestTool`: PITest 执行与变异分数报告解析

  **版本控制**:
  - `GitDiffTool`: 灵活的 Git 差异分析工具 (支持未提交/暂存区/任意 Ref 比较)

  **项目扫描**:
  - `ProjectScannerTool`: Discovers Java source files with exclusion patterns
  - `TestDiscoveryTool`: Finds existing test files

  **知识库与反馈**:
  - `KnowledgeBaseTool`: RAG-style search of existing test patterns and Markdown docs
  - `CoverageFeedbackEngine`: 多轮覆盖率提升引擎
  - `BoundaryAnalyzerTool`: AST 边界条件分析 (if/switch/for/while/null)

## Key Design Patterns

1. **Tool Registration**: `ToolFactory.loadAndWrapTools()` dynamically discovers all `AgentTool` implementations via reflection and wraps them for LangChain4j

2. **Configuration Layering**: `agent.yml` is loaded from multiple locations (classpath → user home → current dir → JAR dir → CLI path), with later sources overriding earlier ones. Environment variables are supported via `${env:VAR_NAME}` syntax.

3. **Project Root Protection**: `FileSystemTool` enforces that all file operations are relative to the detected project root (directory containing `pom.xml`). This prevents path traversal issues.

4. **Retry-Streaming-Tracking 三分离架构**: AgentOrchestrator 重构后职责更清晰
   - `RetryExecutor`: 独立的指数退避重试逻辑 (2^attempt * 1000ms)
   - `StreamingResponseHandler`: 实时输出 LLM 响应，提升用户体验
   - `RepairTracker`: 跟踪修复历史，防止无效循环

5. **统一异常处理**: `AgentToolException` 提供结构化错误信息
   - `ErrorCode` 枚举: FILE_NOT_FOUND, PARSE_ERROR, EXTERNAL_TOOL_ERROR 等
   - Builder 模式构建异常
   - `toAgentMessage()` 生成面向 LLM 的友好提示

6. **自修复增强**: 从简单重试进化为有目的的修复
   - `TestReportTool` 解析 Surefire XML，区分错误类型
   - 编译错: 检查导包、泛型、Mock 类型
   - 断言失败: 分析预期值与实际值差异
   - 环境/依赖错: 建议或修改 pom.xml

7. **动态 Prompt 组装**: `DynamicPromptBuilder` 根据项目规范调整系统提示
   - 注入项目的测试风格 (断言风格、命名规范)
   - 添加边界值测试建议
   - 结合变异测试结果指导增强

8. **增量分析模式**: `IncrementalAnalyzer` 整合 Git 差异
   - 支持三种模式: UNCOMMITTED / STAGED_ONLY / COMPARE_REFS
   - 自动识别变更文件，生成针对性测试任务

9. **质量反馈循环**: `CoverageFeedbackEngine` 多轮迭代提升
   - 结合边界分析和变异测试结果
   - 智能决策: 修改现有测试 vs 新增测试 vs 边界值增强
   - 防止无效循环的迭代历史跟踪

## Configuration

Default configuration is in `src/main/resources/agent.yml`. Runtime configuration is loaded from (later sources override earlier):
1. Classpath (agent.yml in JAR)
2. `~/.unit-test-agent/config.yml` or `~/.unit-test-agent/agent.yml`
3. `./config.yml` or `./agent.yml` (current directory)
4. JAR directory/agent.yml (recommended for distribution)
5. CLI `--config` path (highest priority)

CLI arguments override config file values but don't persist unless `--save` is used.

### 新增配置项

```yaml
# 工作流配置
workflow:
  # 交互模式 - 每次文件写入前确认
  interactive: false

  # 启用 LSP 语法检查 (自动下载 JDT LS 1.50.0)
  use-lsp: false

  # 启用变异测试 (需要 pom.xml 配置 PITest)
  enableMutationTesting: false

  # 最大反馈循环迭代次数
  maxFeedbackLoopIterations: 3

  # 默认 Skill（动态工具选择，减少 token 消耗）
  # default-skill: "full"

# 增量模式配置
incremental:
  # 模式: UNCOMMITTED | STAGED_ONLY | COMPARE_REFS
  mode: UNCOMMITTED

  # COMPARE_REFS 模式下的 Git refs
  # baseRef: "main"
  # targetRef: "HEAD"

  # 文件路径排除模式
  # excludePatterns: ".*Test\\.java"

# Skills 配置 - 动态工具选择（减少 token 消耗）
skills:
  - name: "analysis"
    description: "代码分析阶段"
    tools: [CodeAnalyzerTool, FileSystemTool, BoundaryAnalyzerTool, StyleAnalyzerTool]
  - name: "generation"
    description: "测试生成阶段"
    tools: [FileSystemTool, DirectoryTool, KnowledgeBaseTool, SyntaxCheckerTool]
  - name: "verification"
    description: "测试验证阶段"
    tools: [MavenExecutorTool, TestReportTool, CoverageTool, FileSystemTool]
  - name: "repair"
    description: "修复阶段"
    tools: [FileSystemTool, TestReportTool, CodeAnalyzerTool, SyntaxCheckerTool]
  - name: "full"
    description: "完整工具集（默认）"
    tools: []  # 空数组表示使用全部工具
```

### LLM Protocols

The `llm.protocol` config supports:
- `openai` - Standard OpenAI-compatible API (auto-appends `/v1` to baseUrl)
- `openai-zhipu` - Zhipu AI variant (uses baseUrl as-is, no `/v1` appending)
- `anthropic` - Anthropic Claude API
- `gemini` - Google Gemini API (auto-appends `/v1beta` to baseUrl)

## System Prompt

The system prompt template is at `src/main/resources/prompts/system-prompt.st`. This contains the core instructions given to the LLM, including:
- Workflow steps (analysis → RAG → planning → generation → verification)
- JUnit 5 + Mockito standards (`@ExtendWith(MockitoExtension.class)`)
- Coverage-driven enhancement rules
- Tool usage guidelines

## Testing Dependencies

The project requires specific minimum versions for test generation (defined in `agent.yml`):
- JUnit Jupiter: 5.10.1+
- Mockito Core: 5.8.0+
- mockito-inline: 5.8.0+ (for static mocking)
- JaCoCo Maven Plugin: 0.8.11+ (for Java 21+ compatibility)

## Platform Compatibility

- **Windows**: Uses PowerShell 7 (`pwsh`) if available; handles Windows path encoding
- **Linux/macOS**: Uses standard `sh` and `mvn`
- Maven commands are executed via `MavenExecutorTool` which handles platform differences

## Model Classes

Key domain models in `model/`:
- `Context`: Carries project root, source/test paths
- `TestTask`: Represents a class needing tests (batch mode)
- `UncoveredMethod`: Method with coverage below threshold

## Project Structure Notes

- This project (`unit-test-agent-4j`) has no `src/test/` directory of its own - it is a tool for generating tests in other projects.
- Main class: `com.codelogickeep.agent.ut.App` (configured in maven-shade-plugin)
- Output JAR: `target/unit-test-agent-4j-0.1.0-LITE-shaded.jar`

## Adding New Tools

To add a new tool for the Agent to use:
1. Create a class in `tools/` that implements `AgentTool` marker interface
2. Annotate methods with `@Tool` (LangChain4j annotation) to expose them to the LLM
3. Use `@P` for parameter descriptions
4. The tool will be auto-discovered by `ToolFactory.loadAndWrapTools()` via reflection
5. For initialization needs, check for instanceof in ToolFactory (like `KnowledgeBaseTool`)
6. **可选**: 将工具添加到相关的 skill 配置中，以便按需加载

## 动态工具选择 (Skill-based Tool Selection)

通过 Skill 机制按需传递工具给 LLM，减少 token 消耗（约 60-70%）。

### 使用方式

**CLI 参数指定 skill:**
```bash
java -jar utagent.jar --target Foo.java --skill analysis
```

**配置文件设置默认 skill:**
```yaml
workflow:
  default-skill: "generation"
```

### 内置 Skills

| Skill | 描述 | 工具数量 | 估算节省 |
|-------|------|----------|----------|
| `analysis` | 代码分析阶段 | 6 | ~60% |
| `generation` | 测试生成阶段 | 5 | ~65% |
| `verification` | 测试验证阶段 | 5 | ~65% |
| `repair` | 修复阶段 | 5 | ~65% |
| `full` | 完整工具集 | 全部 | 基准 |

### 自定义 Skill

在 `agent.yml` 中添加自定义 skill:
```yaml
skills:
  - name: "my-custom-skill"
    description: "自定义工具集"
    tools:
      - FileSystemTool
      - CodeAnalyzerTool
      - MavenExecutorTool
```

---

## CLI 参数参考

### 全局选项

| 选项 | 简写 | 描述 | 默认值 |
|------|------|------|--------|
| `--config` | `-c` | 配置文件路径 | 自动检测 |
| `--protocol` | | LLM 协议 | 从配置读取 |
| `--api-key` | | API 密钥 | 从配置/环境变量读取 |
| `--base-url` | | API 基础 URL | 协议默认值 |
| `--model` | `-m` | 模型名称 | 从配置读取 |
| `--temperature` | `-t` | 采样温度 (0.0-1.0) | 0.0 |
| `--timeout` | | 请求超时(秒) | 120 |
| `--max-retries` | | 最大重试次数 | 3 |
| `--verbose` | `-v` | 启用详细日志 | false |
| `--interactive` | `-i` | 启用交互确认模式 | false |
| `--skill` | | 使用指定 skill 的工具子集 | 全部工具 |

### 目标选项

| 选项 | 简写 | 描述 | 示例 |
|------|------|------|------|
| `--target` | | 单个源文件路径 | `src/main/java/Foo.java` |
| `--project` | `-p` | 项目根目录 (批处理模式) | `/path/to/project` |
| `--exclude` | `-e` | 排除模式 (逗号分隔) | `**/dto/**,**/vo/**` |
| `--threshold` | | 覆盖率阈值 % | 80 |
| `--dry-run` | | 仅分析，不生成 | |
| `--batch-limit` | | 最大处理类数 | 无限制 |

### 增量模式选项

| 选项 | 描述 | 示例 |
|------|------|------|
| `--incremental` | 启用增量模式 | |
| `--incremental-mode` | 模式: UNCOMMITTED/STAGED_ONLY/COMPARE_REFS | `COMPARE_REFS` |
| `--base-ref` | 比较基准 Git ref | `main`, `HEAD~1`, `abc123` |
| `--target-ref` | 比较目标 Git ref | `HEAD`, `feature-branch` |

### 知识库选项

| 选项 | 简写 | 描述 | 示例 |
|------|------|------|------|
| `-kb` | | 知识库路径 | `src/test/java` |
| `--kb-types` | | 索引文件类型 | `java,md,yml` |

### 内置排除模式

批处理模式默认排除:
- `**/dto/**`, `**/vo/**`, `**/domain/**`
- `**/*DTO.java`, `**/*VO.java`, `**/*Entity.java`
- `**/*Enum.java`, `**/*Criteria.java`
- `**/dao/**/*DAO.java`, `**/repo/**/*Repo.java`

---

## 开发阶段总览 (doc/plan.md)

| 阶段 | 名称 | 状态 | 优先级 |
|------|------|------|--------|
| Phase 1 | 技术债务清理 | ✅ 已完成 | 最高 |
| Phase 2 | 测试模板与项目规范学习 | ✅ 已完成 | 高 |
| Phase 3 | 测试执行结果分析与自动修复增强 | ✅ 已完成 | 高 |
| Phase 4 | Git 增量检测 | ✅ 已完成 | 中 |
| Phase 5 | 测试质量评估与反馈循环 | ✅ 已完成 | 中 |

### Phase 1: 技术债务清理

- 核心工具单元测试 (FileSystemTool, DirectoryTool, CodeAnalyzerTool, CoverageTool, MavenExecutorTool, ProjectScannerTool, TestDiscoveryTool, KnowledgeBaseTool)
- 统一异常处理 `AgentToolException` (ErrorCode + Context + Builder 模式)
- 配置验证与默认值增强 (ConfigValidator)
- 日志分级精细化 (INFO/DEBUG, -Dut.agent.log.level=DEBUG)
- 项目结构解耦 (RetryExecutor + StreamingResponseHandler)

### Phase 2: 测试模板与项目规范学习

- `StyleAnalyzerTool` - 分析现有测试代码风格 (Mock 偏好、断言风格、命名惯例)
- RAG 知识库增强 - 支持检索 Markdown 文档 (CONTRIBUTING.md, TestingGuide.md)
- 动态 Prompt 组装 (`DynamicPromptBuilder`) - 根据项目规范调整系统提示

### Phase 3: 测试执行结果分析与自动修复增强

- `TestReportTool` - 解析 surefire-reports/*.xml，区分错误类型
- 错误分类修复策略 - 编译错/断言失败/环境依赖错
- `RepairTracker` - 记录修复过程，避免死循环

### Phase 4: Git 增量检测

- JGit 集成 (org.eclipse.jgit 7.1.0)
- `GitDiffTool` - 灵活的 Git 差异分析工具
- 支持三种模式: 未提交变更/暂存区/任意 Ref 比较
- `IncrementalAnalyzer` - 整合 Git 差异与测试任务生成

### Phase 5: 测试质量评估与反馈循环

- `MutationTestTool` - PITest 执行与变异分数报告解析
- `BoundaryAnalyzerTool` - AST 边界条件分析 (if/switch/for/while/null)
- `CoverageFeedbackEngine` - 多轮覆盖率提升引擎

---

## 日志级别

| 级别 | 内容 |
|-------|------|
| `INFO` | 用户进度、关键里程碑 |
| `DEBUG` | 工具输入输出、技术细节 |
| `WARN` | 非致命问题 |
| `ERROR` | 需要关注的失败 |

启用详细日志:
```bash
# 通过命令行
java -jar unit-test-agent-4j.jar --target Foo.java -v

# 通过系统属性
java -Dut.agent.log.level=DEBUG -jar unit-test-agent-4j.jar --target Foo.java
```

---

## 语法检查工具

### JavaParser 快速检查 (SyntaxCheckerTool)

- **速度**: ~10ms
- **功能**: 基本语法验证
- **方法**: `checkSyntax()`, `checkSyntaxContent()`, `validateTestStructure()`

### LSP 语义检查 (LspSyntaxCheckerTool)

- **速度**: 较慢 (~100-500ms)
- **功能**: 完整语义分析 (类型错误、缺失导入)
- **配置**: `workflow.use-lsp: true`
- **方法**: `initializeLsp()`, `checkSyntaxWithLsp()`, `shutdownLsp()`
- **自动下载**: JDT Language Server 1.50.0
- **自动初始化**: 当 `use-lsp: true` 时，在程序启动时自动初始化 LSP 服务（无需 LLM 手动调用）
- **强制使用**: DynamicPromptBuilder 会自动在 System Prompt 中添加强制使用 LSP 的指令
- **启动方式**: 直接使用 Java 命令启动 JDT LS（绕过 Python 脚本，更稳定）
- **错误回退**: LSP 初始化失败时自动回退到 JavaParser，不影响主流程

---

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

---

## 相关文档

- `doc/plan.md` - 开发路线图
- `README.md` - 用户文档和使用指南
- `src/main/resources/prompts/system-prompt.st` - LLM 系统提示模板
