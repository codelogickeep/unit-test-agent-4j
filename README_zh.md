# Unit Test Agent 4j

企业级 Java 单元测试智能体，专注于为遗留系统自动生成高质量的 JUnit 5 + Mockito 测试代码。

[![Java](https://img.shields.io/badge/Java-21+-blue.svg)](https://openjdk.java.net/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-red.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

## 目录

- [核心特性](#核心特性)
- [安装](#安装)
- [快速开始](#快速开始)
- [使用指南](#使用指南)
  - [单文件模式](#单文件模式)
  - [批量模式](#批量模式)
  - [增量模式](#增量模式)
  - [交互模式](#交互模式)
- [命令行参考](#命令行参考)
- [配置说明](#配置说明)
- [可用工具](#可用工具)
- [系统架构](#系统架构)
- [故障排除](#故障排除)
- [参与贡献](#参与贡献)

## 核心特性

| 特性 | 说明 |
|------|------|
| **多模型支持** | 原生支持 OpenAI、Anthropic (Claude)、Gemini 及 OpenAI 兼容代理 |
| **智能环境审计** | 自动检测项目依赖（JUnit 5、Mockito、JaCoCo）及版本兼容性 |
| **自我修复机制** | 自动编译运行测试，根据错误日志修复代码 |
| **标准化测试** | 强制使用 JUnit 5 + Mockito + mockito-inline 标准 |
| **项目根保护** | 通过 `pom.xml` 检测锁定项目根目录，防止路径幻觉 |
| **指数退避重试** | 智能处理 API 速率限制 |
| **RAG 知识库** | 检索现有测试和文档，确保代码风格一致 |
| **覆盖率驱动增强** | 分析未覆盖方法，自动补充测试 |
| **Git 增量检测** | 仅为变更文件生成测试（未提交/暂存/分支间比较） |
| **变异测试** | 集成 PITest 评估测试有效性 |

## 安装

### 前置要求

- **JDK 21+**（必需）
- **Maven 3.8+**（必需）
- **Git**（可选，用于增量模式）

### 从源码构建

```bash
# 克隆仓库
git clone https://github.com/your-org/unit-test-agent-4j.git
cd unit-test-agent-4j

# 构建项目
mvn clean package -DskipTests

# 可执行 JAR 位于：
# target/unit-test-agent-4j-0.1.0-LITE-shaded.jar
```

### 验证安装

```bash
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar --help
```

## 快速开始

### 第一步：配置 API Key

```bash
# 方式 A：使用 config 命令（推荐）
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar config \
  --protocol openai \
  --api-key "sk-your-api-key" \
  --model "gpt-4o"

# 方式 B：设置环境变量
export UT_AGENT_API_KEY="sk-your-api-key"
export UT_AGENT_MODEL_NAME="gpt-4o"
```

### 第二步：生成第一个测试

```bash
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar \
  --target src/main/java/com/example/MyService.java
```

### 第三步：查看输出

Agent 将会：
1. 分析源文件
2. 在 `src/test/java/com/example/MyServiceTest.java` 生成测试类
3. 编译并运行测试
4. 自动修复任何失败

## 使用指南

### 单文件模式

为指定的 Java 源文件生成测试。

```bash
# 基本用法
java -jar unit-test-agent-4j.jar --target path/to/MyService.java

# 使用知识库匹配代码风格
java -jar unit-test-agent-4j.jar \
  --target path/to/MyService.java \
  -kb src/test/java

# 启用交互确认
java -jar unit-test-agent-4j.jar \
  --target path/to/MyService.java \
  --interactive

# 自定义覆盖率阈值
java -jar unit-test-agent-4j.jar \
  --target path/to/MyService.java \
  --threshold 90
```

### 批量模式

扫描整个项目，为所有需要覆盖的类生成测试。

```bash
# 扫描整个项目
java -jar unit-test-agent-4j.jar --project /path/to/project

# 使用排除规则
java -jar unit-test-agent-4j.jar \
  --project /path/to/project \
  --exclude "**/dto/**,**/vo/**,**/entity/**"

# Dry-run 模式（仅分析）
java -jar unit-test-agent-4j.jar \
  --project /path/to/project \
  --dry-run

# 限制批量处理数量
java -jar unit-test-agent-4j.jar \
  --project /path/to/project \
  --batch-limit 10
```

**内置排除规则：**
- `**/dto/**`、`**/vo/**`、`**/domain/**`
- `**/*DTO.java`、`**/*VO.java`、`**/*Entity.java`
- `**/*Enum.java`、`**/*Criteria.java`
- `**/dao/**/*DAO.java`、`**/repo/**/*Repo.java`

### 增量模式

仅为 Git 中变更的文件生成测试。

```bash
# 测试未提交的变更（工作区 + 暂存区）
java -jar unit-test-agent-4j.jar \
  --project /path/to/project \
  --incremental

# 仅测试暂存区变更
java -jar unit-test-agent-4j.jar \
  --project /path/to/project \
  --incremental \
  --incremental-mode STAGED_ONLY

# 比较两个 ref（如：feature 分支 vs main）
java -jar unit-test-agent-4j.jar \
  --project /path/to/project \
  --incremental \
  --incremental-mode COMPARE_REFS \
  --base-ref main \
  --target-ref HEAD

# 比较特定提交
java -jar unit-test-agent-4j.jar \
  --project /path/to/project \
  --incremental \
  --incremental-mode COMPARE_REFS \
  --base-ref abc123 \
  --target-ref def456
```

**增量模式类型：**
| 模式 | 说明 |
|------|------|
| `UNCOMMITTED` | 工作区 + 暂存区的变更（默认） |
| `STAGED_ONLY` | 仅暂存区变更 |
| `COMPARE_REFS` | 比较两个 Git ref（分支、提交、标签） |

### 交互模式

预览并确认每次文件写入操作。

```bash
java -jar unit-test-agent-4j.jar \
  --target path/to/MyService.java \
  -i
```

**交互提示示例：**
```
╔══════════════════════════════════════════════════════════════════╗
║ 写入文件: src/test/java/com/example/MyServiceTest.java          ║
║ 操作类型: 创建新文件                                              ║
╟──────────────────────────────────────────────────────────────────╢
║ 预览（前 30 行）:                                                 ║
║                                                                  ║
║ package com.example;                                             ║
║                                                                  ║
║ import org.junit.jupiter.api.Test;                               ║
║ import org.junit.jupiter.api.extension.ExtendWith;               ║
║ ...                                                              ║
╟──────────────────────────────────────────────────────────────────╢
║ [Y] 确认  [n] 取消  [v] 查看完整内容                              ║
╚══════════════════════════════════════════════════════════════════╝
```

## 命令行参考

### 全局选项

| 选项 | 简写 | 说明 | 默认值 |
|------|------|------|--------|
| `--config` | `-c` | 配置文件路径 | 自动检测 |
| `--protocol` | | LLM 协议 (openai/anthropic/gemini) | 来自配置 |
| `--api-key` | | API 密钥 | 来自配置/环境变量 |
| `--base-url` | | API 基础 URL | 协议默认值 |
| `--model` | `-m` | 模型名称 | 来自配置 |
| `--temperature` | `-t` | 采样温度 (0.0-1.0) | 0.0 |
| `--timeout` | | 请求超时（秒） | 120 |
| `--max-retries` | | 最大重试次数 | 3 |
| `--verbose` | `-v` | 启用详细日志 | false |
| `--help` | `-h` | 显示帮助信息 | |

### 目标选项

| 选项 | 简写 | 说明 | 示例 |
|------|------|------|------|
| `--target` | | 单个源文件路径 | `src/main/java/Foo.java` |
| `--project` | `-p` | 批量模式的项目根目录 | `/path/to/project` |
| `--exclude` | `-e` | 排除规则（逗号分隔） | `**/dto/**,**/vo/**` |
| `--threshold` | | 覆盖率阈值 % | 80 |
| `--dry-run` | | 仅分析，不生成 | |
| `--batch-limit` | | 最大处理类数 | 无限制 |

### 增量选项

| 选项 | 说明 | 示例 |
|------|------|------|
| `--incremental` | 启用增量模式 | |
| `--incremental-mode` | 模式：UNCOMMITTED/STAGED_ONLY/COMPARE_REFS | `COMPARE_REFS` |
| `--base-ref` | 比较的基准 Git ref | `main`、`HEAD~1`、`abc123` |
| `--target-ref` | 比较的目标 Git ref | `HEAD`、`feature-branch` |

### 知识库选项

| 选项 | 简写 | 说明 | 示例 |
|------|------|------|------|
| `-kb` | | 知识库路径 | `src/test/java` |
| `--kb-types` | | 索引的文件类型 | `java,md,yml` |

### 交互选项

| 选项 | 简写 | 说明 |
|------|------|------|
| `--interactive` | `-i` | 启用交互确认模式 |

## 配置说明

### 配置文件位置

Agent 按以下顺序搜索 `agent.yml`：

1. `--config` 命令行参数
2. **JAR 所在目录**（推荐）
3. 当前工作目录
4. 用户主目录（`~/.unit-test-agent/`）

### 完整配置参考 (`agent.yml`)

```yaml
# ═══════════════════════════════════════════════════════════════════
# LLM 设置
# ═══════════════════════════════════════════════════════════════════
llm:
  # 协议：openai | openai-zhipu | anthropic | gemini
  protocol: "openai"
  
  # API 密钥 - 支持环境变量语法
  apiKey: "${env:UT_AGENT_API_KEY}"
  
  # 基础 URL - 自动处理 /v1 或 /v1beta 后缀
  # 示例：
  #   - OpenAI：https://api.openai.com（默认）
  #   - Azure：https://your-resource.openai.azure.com
  #   - 阿里云：https://dashscope.aliyuncs.com/compatible-mode/v1
  baseUrl: "${env:UT_AGENT_BASE_URL}"
  
  # 模型名称
  modelName: "${env:UT_AGENT_MODEL_NAME}"
  
  # 温度：0.0（精确）到 1.0（创造性）
  # 推荐：代码生成使用 0.0
  temperature: 0.0
  
  # 请求超时（秒）
  timeout: 120
  
  # 自定义 HTTP 头（用于代理或认证）
  customHeaders:
    # X-Custom-Header: "value"

# ═══════════════════════════════════════════════════════════════════
# 工作流设置
# ═══════════════════════════════════════════════════════════════════
workflow:
  # 失败后最大重试次数（指数退避）
  maxRetries: 3
  
  # 覆盖率阈值（%）- 未达标时自动补充测试
  coverageThreshold: 80
  
  # 交互模式 - 每次写入文件前确认
  interactive: false

# ═══════════════════════════════════════════════════════════════════
# 批量模式设置
# ═══════════════════════════════════════════════════════════════════
batch:
  # 排除规则（glob 模式，逗号分隔）
  excludePatterns: "**/dto/**,**/vo/**,**/entity/**"
  
  # Dry-run 模式 - 仅分析，不生成
  dryRun: false

# ═══════════════════════════════════════════════════════════════════
# 增量模式设置
# ═══════════════════════════════════════════════════════════════════
incremental:
  # 模式：UNCOMMITTED | STAGED_ONLY | COMPARE_REFS
  mode: UNCOMMITTED
  
  # COMPARE_REFS 模式的 Git ref
  # baseRef: "main"
  # targetRef: "HEAD"

# ═══════════════════════════════════════════════════════════════════
# 推荐依赖（环境自检使用）
# ═══════════════════════════════════════════════════════════════════
dependencies:
  junit-jupiter: "5.10.1"
  mockito-core: "5.8.0"
  mockito-junit-jupiter: "5.8.0"
  mockito-inline: "5.8.0"
  jacoco-maven-plugin: "0.8.11"

# ═══════════════════════════════════════════════════════════════════
# Prompts 配置
# ═══════════════════════════════════════════════════════════════════
prompts:
  # 系统提示词模板路径（文件或 classpath 资源）
  system: "prompts/system-prompt.st"
```

### 环境变量

| 变量 | 说明 | 示例 |
|------|------|------|
| `UT_AGENT_API_KEY` | LLM API 密钥 | `sk-xxx` |
| `UT_AGENT_BASE_URL` | LLM API 基础 URL | `https://api.openai.com` |
| `UT_AGENT_MODEL_NAME` | 模型名称 | `gpt-4o` |

### 协议配置示例

#### OpenAI / OpenAI 兼容

```yaml
llm:
  protocol: "openai"
  apiKey: "sk-..."
  modelName: "gpt-4o"
  # 代理配置：
  # baseUrl: "https://your-proxy.com/v1"
```

#### Anthropic (Claude)

```yaml
llm:
  protocol: "anthropic"
  apiKey: "sk-ant-..."
  modelName: "claude-3-5-sonnet-20240620"
```

#### Google Gemini

```yaml
llm:
  protocol: "gemini"
  apiKey: "AI..."
  modelName: "gemini-1.5-pro"
```

#### 阿里云（百炼 / DashScope）

```yaml
llm:
  protocol: "openai"
  apiKey: "sk-..."
  baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1"
  modelName: "qwen-max"
```

## 可用工具

Agent 可以使用以下工具：

### 文件系统工具

| 工具 | 说明 |
|------|------|
| `readFile` | 读取文件内容 |
| `writeFile` | 写入文件内容 |
| `fileExists` | 检查文件是否存在 |
| `searchReplace` | 文件中搜索替换 |
| `listFiles` | 列出目录内容 |
| `createDirectory` | 创建目录 |

### 代码分析工具

| 工具 | 说明 |
|------|------|
| `analyzeClass` | 分析 Java 类结构（方法、字段、依赖） |
| `analyzeMethod` | 获取方法详细分析（复杂度、分支） |
| `getMethodsForTesting` | 列出适合测试的公共方法 |
| `scanProjectClasses` | 扫描项目源类 |

### Maven 工具

| 工具 | 说明 |
|------|------|
| `compileProject` | 运行 `mvn compile` |
| `executeTest` | 运行特定测试类 |

### 覆盖率工具

| 工具 | 说明 |
|------|------|
| `getCoverageReport` | 获取整体覆盖率摘要 |
| `checkCoverageThreshold` | 检查类是否达到覆盖率阈值 |
| `getMethodCoverageDetails` | 获取方法级覆盖率详情 |

### Git 工具

| 工具 | 说明 |
|------|------|
| `getUncommittedChanges` | 列出未提交的文件变更 |
| `getStagedChanges` | 列出暂存的文件变更 |
| `getChangesBetweenRefs` | 列出两个 Git ref 之间的变更 |
| `listBranches` | 列出所有分支 |
| `getFileDiff` | 获取文件的详细差异 |

### 知识库工具

| 工具 | 说明 |
|------|------|
| `searchKnowledge` | 在知识库中搜索模式 |
| `searchTestingGuidelines` | 查找测试规范 |
| `searchTestExamples` | 查找现有测试示例 |

## 系统架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CLI / 用户输入                              │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          配置加载器                                 │
│  • 加载 agent.yml                                                   │
│  • 验证配置 (ConfigValidator)                                       │
│  • 应用默认值                                                       │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          环境审计器                                 │
│  • 检查 JDK 版本                                                    │
│  • 验证 Maven 安装                                                  │
│  • 扫描 pom.xml 依赖                                                │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        Agent 编排器                                 │
│  ┌─────────────────────┐  ┌─────────────────────────────────────┐  │
│  │   RetryExecutor     │  │   StreamingResponseHandler          │  │
│  │  • 指数退避         │  │  • 实时输出                          │  │
│  │  • 可配置参数       │  │  • Token 统计                        │  │
│  └─────────────────────┘  └─────────────────────────────────────┘  │
│  ┌─────────────────────┐  ┌─────────────────────────────────────┐  │
│  │  RepairTracker      │  │   DynamicPromptBuilder              │  │
│  │  • 修复历史跟踪     │  │  • 项目感知提示词                    │  │
│  │  • 避免死循环       │  │  • 风格指南                          │  │
│  └─────────────────────┘  └─────────────────────────────────────┘  │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      LangChain4j AI 服务                            │
│  • 流式聊天模型                                                      │
│  • 工具执行                                                         │
│  • 内存管理                                                         │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                           工具层                                    │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐  │
│  │ FileSystem  │ │ CodeAnalyzer│ │   Maven     │ │  Coverage   │  │
│  │    Tool     │ │    Tool     │ │  Executor   │ │    Tool     │  │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘  │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐  │
│  │  Directory  │ │  GitDiff    │ │ Knowledge   │ │   Style     │  │
│  │    Tool     │ │    Tool     │ │   Base      │ │  Analyzer   │  │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

## 故障排除

### 常见问题

#### 1. API Key 未找到

**错误：** `LLM API Key is missing`

**解决方案：**
```bash
# 通过环境变量设置
export UT_AGENT_API_KEY="your-key"

# 或通过 config 命令设置
java -jar unit-test-agent-4j.jar config --api-key "your-key"
```

#### 2. Maven 未找到

**错误：** `Maven not found in PATH`

**解决方案：**
- 安装 Maven 3.8+
- 将 Maven 添加到 PATH
- 验证：`mvn --version`

#### 3. JaCoCo 报告未找到

**错误：** `JaCoCo coverage report not found`

**解决方案：**
```bash
# 确保 pom.xml 中配置了 JaCoCo
# 运行测试生成覆盖率报告
mvn clean test jacoco:report
```

#### 4. 速率限制超出

**错误：** `Rate limit exceeded`

**解决方案：**
- Agent 会自动使用指数退避重试
- 如需要可增加 `--timeout` 和 `--max-retries`
- 考虑使用更高级别的 API 套餐

#### 5. 测试编译失败

**错误：** `Compilation failed`

**解决方案：**
- Agent 会尝试自动修复
- 检查 pom.xml 中是否有所有依赖
- 验证 Mockito 版本兼容性

### 启用详细日志

```bash
# 通过命令行
java -jar unit-test-agent-4j.jar --target Foo.java -v

# 通过系统属性
java -Dut.agent.log.level=DEBUG -jar unit-test-agent-4j.jar --target Foo.java
```

### 日志级别

| 级别 | 内容 |
|------|------|
| `INFO` | 用户进度、关键里程碑 |
| `DEBUG` | 工具输入/输出、技术细节 |
| `WARN` | 非致命问题 |
| `ERROR` | 需要关注的失败 |

## 参与贡献

欢迎贡献！请参阅 [CONTRIBUTING.md](CONTRIBUTING.md) 了解指南。

### 开发环境设置

```bash
# 克隆仓库
git clone https://github.com/your-org/unit-test-agent-4j.git
cd unit-test-agent-4j

# 构建并测试
mvn clean verify

# 运行特定测试
mvn test -Dtest=FileSystemToolTest
```

### 项目结构

```
unit-test-agent-4j/
├── src/main/java/com/codelogickeep/agent/ut/
│   ├── cli/              # CLI 入口
│   ├── config/           # 配置加载与验证
│   ├── engine/           # 核心编排
│   │   ├── AgentOrchestrator.java
│   │   ├── RetryExecutor.java
│   │   ├── StreamingResponseHandler.java
│   │   ├── DynamicPromptBuilder.java
│   │   └── RepairTracker.java
│   ├── exception/        # 自定义异常
│   └── tools/            # Agent 工具
│       ├── FileSystemTool.java
│       ├── CodeAnalyzerTool.java
│       ├── CoverageTool.java
│       ├── GitDiffTool.java
│       └── ...
├── src/test/java/        # 单元测试
├── doc/                  # 文档
├── prompts/              # 提示词模板
└── pom.xml
```

## 许可证

本项目采用 Apache License 2.0 许可证 - 详见 [LICENSE](LICENSE) 文件。

---

**献给那些厌倦手写测试的 Java 开发者 ❤️**
