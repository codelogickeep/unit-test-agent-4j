# Unit Test Agent 4j

An enterprise-grade Java Unit Test Agent that automatically generates high-quality JUnit 5 + Mockito test code for legacy systems.

[![Java](https://img.shields.io/badge/Java-21+-blue.svg)](https://openjdk.java.net/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-red.svg)](https://maven.apache.org/)
[![Version](https://img.shields.io/badge/Version-1.1.0-green.svg)](https://github.com/codelogickeep/unit-test-agent-4j)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

## What's New in v1.1.0

- 🚀 **Custom Lightweight Agent Framework** - Replaced LangChain4j with a purpose-built framework
- 🔧 **Better Zhipu AI Support** - Fixed message format issues (1214 error)
- 📊 **Improved Context Management** - Smart message trimming that maintains valid conversation sequence
- ⚡ **Reduced Dependencies** - Lighter JAR size (~50% smaller)

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Usage Guide](#usage-guide)
  - [Single File Mode](#single-file-mode)
  - [Batch Mode](#batch-mode)
  - [Incremental Mode](#incremental-mode)
  - [Interactive Mode](#interactive-mode)
- [Command Line Reference](#command-line-reference)
- [Configuration](#configuration)
- [Available Tools](#available-tools)
- [Architecture](#architecture)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)

## Features

| Feature | Description |
|---------|-------------|
| **Multi-Model Support** | Native support for OpenAI, Anthropic (Claude), Gemini, Zhipu AI, and OpenAI-compatible APIs |
| **Custom Agent Framework** | Lightweight, purpose-built framework (no LangChain4j dependency) |
| **Intelligent Environment Audit** | Auto-detects project dependencies (JUnit 5, Mockito, JaCoCo) and version compatibility |
| **Self-Healing Mechanism** | Automatically compiles and runs tests, repairs code based on error logs |
| **Standardized Testing** | Enforces JUnit 5 + Mockito + mockito-inline standards |
| **Project Root Protection** | Locks project root via `pom.xml` detection, prevents path hallucinations |
| **Exponential Backoff Retry** | Handles API rate limits with intelligent retry logic |
| **RAG Knowledge Base** | Retrieves existing tests and docs to ensure consistent code style |
| **Coverage-Driven Enhancement** | Analyzes uncovered methods and supplements tests automatically |
| **Git Incremental Detection** | Only generates tests for changed files (uncommitted/staged/between refs) |
| **Mutation Testing** | Integrates PITest to assess test effectiveness |
| **LSP Syntax Checking** | Optional Eclipse JDT Language Server integration for semantic analysis (auto-download) |
| **Pre-compile Validation** | JavaParser-based fast syntax checking before compilation |
| **Iterative Method Testing** | Generate tests one method at a time with priority-based ordering |
| **Interactive Mode** | Preview and confirm file writes before applying changes (safety-first workflow) |

## Installation

### Prerequisites

- **JDK 21+** (required)
- **Maven 3.8+** (required, for building from source)
- **Git** (required, for cloning and building)

### Quick Install (Recommended)

The easiest way to install is to use our automated build script that clones the source, checks the environment, builds the project, and installs it for you.

#### Linux / macOS

```bash
# Clone, build, and install in one command
curl -sSL https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/build.sh | bash

# Add to PATH (add to your ~/.bashrc or ~/.zshrc)
export PATH="$PATH:$HOME/.utagent"
```

#### Windows (PowerShell 7+)

```powershell
# Clone, build, and install in one command
irm https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/build.ps1 | iex

# Add to PATH manually: $env:USERPROFILE\.utagent
```

The build script will:
1. Check your environment (Java 21+, Maven 3.8+, Git)
2. Clone the source code
3. Build the project with Maven (takes 2-5 minutes)
4. Install `utagent` command to `~/.utagent` (or `%USERPROFILE%\.utagent` on Windows)
5. Clean up build files

After installation:

```bash
# Configure API key
utagent config --protocol openai --api-key "sk-..." --model "gpt-4"

# Generate tests
utagent --target src/main/java/com/example/MyService.java
```

### Manual Build from Source

```bash
# Clone the repository
git clone https://github.com/codelogickeep/unit-test-agent-4j.git
cd unit-test-agent-4j

# Build the project
mvn clean package -DskipTests

# The executable JAR is at:
# target/utagent.jar
```

### Verify Installation

```bash
# If using one-click install
utagent --help

# If building from source
java -jar target/utagent.jar --help
```

## Quick Start

### Step 1: Configure API Key

```bash
# Option A: Use config command (recommended)
utagent config \
  --protocol openai \
  --api-key "sk-your-api-key" \
  --model "gpt-4o"

# Option B: Set environment variable
export OPENAI_API_KEY="sk-your-api-key"
```

### Step 2: Generate Your First Test

```bash
# If using one-click install
utagent --target src/main/java/com/example/MyService.java

# If building from source
java -jar target/utagent.jar --target src/main/java/com/example/MyService.java
```

### Step 3: Check the Output

The agent will:
1. Analyze the source file
2. Generate test class at `src/test/java/com/example/MyServiceTest.java`
3. Compile and run the tests
4. Fix any failures automatically

## Usage Guide

### Single File Mode

Generate tests for a specific Java source file.

```bash
# Basic usage
java -jar utagent.jar --target path/to/MyService.java

# With knowledge base for style matching
java -jar utagent.jar \
  --target path/to/MyService.java \
  -kb src/test/java

# With interactive confirmation
java -jar utagent.jar \
  --target path/to/MyService.java \
  --interactive

# With custom coverage threshold
java -jar utagent.jar \
  --target path/to/MyService.java \
  --threshold 90
```

### Batch Mode

Scan an entire project and generate tests for all classes needing coverage.

```bash
# Scan entire project
java -jar utagent.jar --project /path/to/project

# With exclusion rules
java -jar utagent.jar \
  --project /path/to/project \
  --exclude "**/dto/**,**/vo/**,**/entity/**"

# Dry-run mode (analysis only)
java -jar utagent.jar \
  --project /path/to/project \
  --dry-run

# Limit batch size
java -jar utagent.jar \
  --project /path/to/project \
  --batch-limit 10
```

**Built-in Exclusion Patterns:**
- `**/dto/**`, `**/vo/**`, `**/domain/**`
- `**/*DTO.java`, `**/*VO.java`, `**/*Entity.java`
- `**/*Enum.java`, `**/*Criteria.java`
- `**/dao/**/*DAO.java`, `**/repo/**/*Repo.java`

### Incremental Mode

Only generate tests for files changed in Git.

```bash
# Test uncommitted changes (working directory + staged)
java -jar utagent.jar \
  --project /path/to/project \
  --incremental

# Test only staged changes
java -jar utagent.jar \
  --project /path/to/project \
  --incremental \
  --incremental-mode STAGED_ONLY

# Compare two refs (e.g., feature branch vs main)
java -jar utagent.jar \
  --project /path/to/project \
  --incremental \
  --incremental-mode COMPARE_REFS \
  --base-ref main \
  --target-ref HEAD

# Compare specific commits
java -jar utagent.jar \
  --project /path/to/project \
  --incremental \
  --incremental-mode COMPARE_REFS \
  --base-ref abc123 \
  --target-ref def456
```

**Incremental Modes:**
| Mode | Description |
|------|-------------|
| `UNCOMMITTED` | Changes in working directory + staged (default) |
| `STAGED_ONLY` | Only staged changes |
| `COMPARE_REFS` | Compare two Git refs (branches, commits, tags) |

### Interactive Mode

Preview and confirm each file write operation before the agent modifies your codebase. This mode provides an extra layer of safety and control.

#### When to Use Interactive Mode

**✅ Recommended for:**
- **Production codebases** - Review changes before they're applied
- **Critical systems** - Ensure test code quality before committing
- **Learning/Review** - Understand what the agent generates before accepting
- **First-time usage** - Get familiar with agent behavior
- **Custom test patterns** - Verify generated tests match your project's style

**❌ Not needed for:**
- **Development/testing environments** - Automated workflows
- **CI/CD pipelines** - Non-interactive execution required
- **Batch processing** - Too many confirmations would slow down

#### Usage

```bash
# Enable interactive mode via command line
java -jar utagent.jar \
  --target path/to/MyService.java \
  --interactive

# Or use short form
java -jar utagent.jar \
  --target path/to/MyService.java \
  -i

# Or configure in agent.yml
workflow:
  interactive: true
```

#### Interactive Workflow

When interactive mode is enabled, the agent will pause before each file write operation and show you:

1. **File Preview** - First 30 lines of the file to be written
2. **Operation Type** - CREATE NEW FILE, OVERWRITE EXISTING, or APPEND TO FILE
3. **File Path** - Exact location where the file will be written

**Example Interactive Prompt:**
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
║ import org.mockito.InjectMocks;                                  ║
║ import org.mockito.Mock;                                          ║
║ import org.mockito.junit.jupiter.MockitoExtension;               ║
║ ...                                                              ║
╟──────────────────────────────────────────────────────────────────╢
║ [Y] Confirm  [n] Cancel  [v] View full content                   ║
╚══════════════════════════════════════════════════════════════════╝
```

**Available Actions:**
- **`Y` or `Enter`** - Confirm and write the file
- **`n`** - Cancel this write operation (agent will continue with other operations)
- **`v`** - View the full file content before deciding

#### Use Cases

**1. Review Generated Test Code**
```bash
# Generate tests with preview
java -jar utagent.jar --target MyService.java --interactive

# Review each test file before accepting
# Agent shows: "About to create MyServiceTest.java"
# You can: View full content, confirm, or skip
```

**2. Protect Existing Test Files**
```bash
# Agent detects existing test file
# Shows: "About to OVERWRITE existing MyServiceTest.java"
# You can: Review changes, confirm, or cancel to preserve existing tests
```

**3. Incremental Test Generation**
```bash
# In iterative mode with interactive enabled
# Agent generates one method's tests at a time
# You review and confirm each batch before proceeding
```

**4. Learning Agent Behavior**
```bash
# First time using the agent? Enable interactive mode
# See exactly what code is generated
# Understand test patterns and structure
# Build confidence before running in automated mode
```

#### Configuration

You can enable interactive mode in three ways:

1. **Command Line Flag** (highest priority)
   ```bash
   --interactive  # or -i
   ```

2. **Configuration File**
   ```yaml
   workflow:
     interactive: true
   ```

3. **Default Behavior**
   - Default: `false` (non-interactive)
   - Set to `true` for safety-first workflows

### Iterative Method Testing Mode

Generate tests **one method at a time** with immediate feedback. Ideal for large classes.

```bash
# Enable iterative mode
java -jar utagent.jar \
  --config agent.yml \
  --target path/to/LargeService.java
```

With `agent.yml`:
```yaml
workflow:
  iterative-mode: true          # Enable per-method iteration
  method-coverage-threshold: 80 # Per-method threshold
  skip-low-priority: false      # Skip getters/setters if coverage is met
```

**Benefits:**
- Immediate feedback per method
- Easier error location and fixing
- Better token efficiency for large classes
- Priority-based testing (core functions first)

**Method Priority Classification:**
| Priority | Criteria | Examples |
|----------|----------|----------|
| P0 (Core) | Complexity ≥5, public APIs, frequently called | `processPayment()`, `validateOrder()` |
| P1 (Standard) | Complexity 3-4, public/protected | `setConfig()`, `loadData()` |
| P2 (Low) | Getters, setters, simple constructors | `getId()`, `setName()` |

### Skill-based Tool Selection

Reduce token usage by loading only relevant tools for specific tasks.

```bash
# Use analysis skill (6 tools instead of 15)
java -jar utagent.jar \
  --target path/to/MyService.java \
  --skill analysis

# Use generation skill for test writing
java -jar utagent.jar \
  --target path/to/MyService.java \
  --skill generation

# Use verification skill for running tests
java -jar utagent.jar \
  --target path/to/MyService.java \
  --skill verification
```

**Built-in Skills:**

| Skill | Description | Tools | Token Savings |
|-------|-------------|-------|---------------|
| `analysis` | Code analysis phase | 6 | ~60% |
| `generation` | Test generation phase | 5 | ~65% |
| `verification` | Test verification phase | 5 | ~65% |
| `repair` | Fix failing tests | 5 | ~65% |
| `iterative` | Per-method test generation | 9 | ~40% |
| `full` | All tools (default) | 16 | baseline |

**Interactive Prompts:**
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

## Command Line Reference

### Global Options

| Option | Short | Description | Default |
|--------|-------|-------------|---------|
| `--config` | `-c` | Path to config file | auto-detect |
| `--protocol` | | LLM protocol (openai/anthropic/gemini/openai-zhipu) | from config |
| `--api-key` | | API key | from config/env |
| `--base-url` | | API base URL | protocol default |
| `--model` | `-m` | Model name | from config |
| `--temperature` | `-t` | Sampling temperature (0.0-1.0) | 0.0 |
| `--timeout` | | Request timeout in seconds | 120 |
| `--max-retries` | | Max retry attempts | 3 |
| `--verbose` | `-v` | Enable verbose logging | false |
| `--help` | `-h` | Show help message | |

### Target Options

| Option | Short | Description | Example |
|--------|-------|-------------|---------|
| `--target` | | Single source file path | `src/main/java/Foo.java` |
| `--project` | `-p` | Project root for batch mode | `/path/to/project` |
| `--exclude` | `-e` | Exclusion patterns (comma-separated) | `**/dto/**,**/vo/**` |
| `--threshold` | | Coverage threshold % | 80 |
| `--dry-run` | | Analysis only, no generation | |
| `--batch-limit` | | Max classes to process | unlimited |

### Incremental Options

| Option | Description | Example |
|--------|-------------|---------|
| `--incremental` | Enable incremental mode | |
| `--incremental-mode` | Mode: UNCOMMITTED/STAGED_ONLY/COMPARE_REFS | `COMPARE_REFS` |
| `--base-ref` | Base Git ref for comparison | `main`, `HEAD~1`, `abc123` |
| `--target-ref` | Target Git ref for comparison | `HEAD`, `feature-branch` |

### Knowledge Base Options

| Option | Short | Description | Example |
|--------|-------|-------------|---------|
| `-kb` | | Knowledge base path | `src/test/java` |
| `--kb-types` | | File types to index | `java,md,yml` |

### Interactive Options

| Option | Short | Description |
|--------|-------|-------------|
| `--interactive` | `-i` | Enable interactive confirmation mode |

### Skill Options (Dynamic Tool Selection)

| Option | Description | Example |
|--------|-------------|---------|
| `--skill` | Use specific skill's tool subset | `analysis`, `generation`, `verification` |

## Configuration

### Configuration File Locations

The agent searches for `agent.yml` in the following order:

1. `--config` CLI parameter
2. **JAR directory** (recommended)
3. Current working directory
4. User home directory (`~/.unit-test-agent/`)

### Full Configuration Reference (`agent.yml`)

```yaml
# ═══════════════════════════════════════════════════════════════════
# LLM Settings
# ═══════════════════════════════════════════════════════════════════
llm:
  # Protocol: openai | openai-zhipu | anthropic | gemini
  protocol: "openai"
  
  # API Key - supports environment variable syntax
  apiKey: "${env:UT_AGENT_API_KEY}"
  
  # Base URL - auto-handles /v1 or /v1beta suffixes
  # Examples:
  #   - OpenAI: https://api.openai.com (default)
  #   - Azure: https://your-resource.openai.azure.com
  #   - Zhipu Coding: https://open.bigmodel.cn/api/coding/paas/v4
  #   - Alibaba: https://dashscope.aliyuncs.com/compatible-mode/v1
  baseUrl: "${env:UT_AGENT_BASE_URL}"
  
  # Model name
  modelName: "${env:UT_AGENT_MODEL_NAME}"
  
  # Temperature: 0.0 (precise) to 1.0 (creative)
  # Recommended: 0.0 for code generation
  temperature: 0.0
  
  # Request timeout in seconds
  timeout: 120
  
  # Custom HTTP headers (for proxies or auth)
  customHeaders:
    # X-Custom-Header: "value"

# ═══════════════════════════════════════════════════════════════════
# Workflow Settings
# ═══════════════════════════════════════════════════════════════════
workflow:
  # Max retries on failure (with exponential backoff)
  maxRetries: 3
  
  # Coverage threshold (%) - auto-supplements tests if not reached
  coverageThreshold: 80
  
  # Interactive mode - preview and confirm before each file write operation
  # When enabled, agent pauses before writing/modifying files and shows:
  #   - File preview (first 30 lines)
  #   - Operation type (CREATE/OVERWRITE/APPEND)
  #   - File path
  # Use cases: production codebases, critical systems, learning agent behavior
  # Default: false (non-interactive, suitable for CI/CD and batch processing)
  interactive: false
  
  # Enable LSP syntax checking (auto-downloads JDT LS 1.50.0)
  # Provides full semantic analysis: type errors, missing imports
  use-lsp: false
  
  # Enable iterative method testing mode
  iterative-mode: false
  
  # Per-method coverage threshold (for iterative mode)
  method-coverage-threshold: 80
  
  # Skip low-priority methods (getters/setters) when coverage met
  skip-low-priority: false

# ═══════════════════════════════════════════════════════════════════
# Batch Mode Settings
# ═══════════════════════════════════════════════════════════════════
batch:
  # Exclusion patterns (glob, comma-separated)
  excludePatterns: "**/dto/**,**/vo/**,**/entity/**"
  
  # Dry-run mode - analyze only, no generation
  dryRun: false

# ═══════════════════════════════════════════════════════════════════
# Incremental Mode Settings
# ═══════════════════════════════════════════════════════════════════
incremental:
  # Mode: UNCOMMITTED | STAGED_ONLY | COMPARE_REFS
  mode: UNCOMMITTED
  
  # Git refs for COMPARE_REFS mode
  # baseRef: "main"
  # targetRef: "HEAD"
  
  # Exclusion patterns for file paths
  # excludePatterns: ".*Test\\.java"

# ═══════════════════════════════════════════════════════════════════
# Recommended Dependencies (for environment self-check)
# ═══════════════════════════════════════════════════════════════════
dependencies:
  junit-jupiter: "5.10.1"
  mockito-core: "5.8.0"
  mockito-junit-jupiter: "5.8.0"
  mockito-inline: "5.8.0"
  jacoco-maven-plugin: "0.8.11"

# ═══════════════════════════════════════════════════════════════════
# Prompts Configuration
# ═══════════════════════════════════════════════════════════════════
prompts:
  # System prompt template path (file or classpath resource)
  system: "prompts/system-prompt.st"

# ═══════════════════════════════════════════════════════════════════
# MCP Configuration (Model Context Protocol)
# ═══════════════════════════════════════════════════════════════════
mcp:
  servers: []

# ═══════════════════════════════════════════════════════════════════
# Skills Configuration (Dynamic Tool Selection)
# Reduces token usage by ~60-70% by only loading relevant tools
# ═══════════════════════════════════════════════════════════════════
skills:
  - name: "analysis"
    description: "Code analysis phase"
    tools: [CodeAnalyzerTool, FileSystemTool, BoundaryAnalyzerTool, StyleAnalyzerTool, ProjectScannerTool, TestDiscoveryTool]
  - name: "generation"
    description: "Test generation phase"
    tools: [FileSystemTool, DirectoryTool, KnowledgeBaseTool, SyntaxCheckerTool, CodeAnalyzerTool]
  - name: "verification"
    description: "Test verification phase"
    tools: [MavenExecutorTool, TestReportTool, CoverageTool, FileSystemTool, MutationTestTool]
  - name: "repair"
    description: "Repair phase"
    tools: [FileSystemTool, TestReportTool, CodeAnalyzerTool, SyntaxCheckerTool, MavenExecutorTool]
  - name: "full"
    description: "Full toolset (default)"
    tools: []  # Empty = use all tools
```

### Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `UT_AGENT_API_KEY` | LLM API key | `sk-xxx` |
| `UT_AGENT_BASE_URL` | LLM API base URL | `https://api.openai.com` |
| `UT_AGENT_MODEL_NAME` | Model name | `gpt-4o` |

### Protocol-Specific Configuration

#### OpenAI / OpenAI-Compatible

```yaml
llm:
  protocol: "openai"
  apiKey: "sk-..."
  modelName: "gpt-4o"
  # For proxies:
  # baseUrl: "https://your-proxy.com/v1"
```

#### Zhipu AI (GLM)

```yaml
llm:
  protocol: "openai-zhipu"
  apiKey: "your-zhipu-api-key"
  modelName: "glm-4.7"
  baseUrl: "https://open.bigmodel.cn/api/coding/paas/v4"
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

#### Alibaba Cloud (Bailian / DashScope)

```yaml
llm:
  protocol: "openai"
  apiKey: "sk-..."
  baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1"
  modelName: "qwen-max"
```

## Available Tools

The agent has access to the following tools:

### File System Tools

| Tool | Description |
|------|-------------|
| `readFile` | Read file contents |
| `writeFile` | Write content to file |
| `fileExists` | Check if file exists |
| `searchReplace` | Search and replace in file |
| `listFiles` | List directory contents |
| `createDirectory` | Create directory |

### Code Analysis Tools

| Tool | Description |
|------|-------------|
| `analyzeClass` | Analyze Java class structure (methods, fields, dependencies) |
| `analyzeMethod` | Get detailed method analysis (complexity, branches) |
| `getMethodsForTesting` | List public methods suitable for testing |
| `getPriorityMethods` | Get prioritized method list for iterative testing |
| `scanProjectClasses` | Scan project for source classes |

### Syntax Checking Tools

| Tool | Description |
|------|-------------|
| `checkSyntax` | Fast JavaParser-based syntax validation (~10ms) |
| `checkSyntaxContent` | Check syntax of code content string |
| `validateTestStructure` | Verify JUnit 5 test class patterns (@ExtendWith, @Mock, @Test) |
| `initializeLsp` | Initialize JDT Language Server for semantic checking |
| `checkSyntaxWithLsp` | Full semantic analysis (type errors, missing imports) |
| `shutdownLsp` | Release LSP server resources |

### Maven Tools

| Tool | Description |
|------|-------------|
| `compileProject` | Run `mvn compile` |
| `executeTest` | Run specific test class |

### Coverage Tools

| Tool | Description |
|------|-------------|
| `getCoverageReport` | Get overall coverage summary |
| `checkCoverageThreshold` | Check if class meets coverage threshold |
| `getMethodCoverageDetails` | Get method-level coverage details |
| `getSingleMethodCoverage` | Get coverage for a single method (iterative mode) |

### Git Tools

| Tool | Description |
|------|-------------|
| `getUncommittedChanges` | List uncommitted file changes |
| `getStagedChanges` | List staged file changes |
| `getChangesBetweenRefs` | List changes between two Git refs |
| `listBranches` | List all branches |
| `getFileDiff` | Get detailed diff for a file |

### Knowledge Base Tools

| Tool | Description |
|------|-------------|
| `searchKnowledge` | Search knowledge base for patterns |
| `searchTestingGuidelines` | Find testing conventions |
| `searchTestExamples` | Find existing test examples |

### Iterative Testing Tools

| Tool | Description |
|------|-------------|
| `initMethodIteration` | Initialize method iteration for a class |
| `getNextMethod` | Get next method to test |
| `completeCurrentMethod` | Mark current method as completed |
| `getIterationProgress` | Get iteration progress summary |
| `skipLowPriorityMethods` | Skip remaining low-priority methods |

## Architecture

```mermaid
flowchart TB
    subgraph Input["🖥️ Input Layer"]
        CLI[CLI / User Input]
    end

    subgraph Config["⚙️ Configuration"]
        CL[Configuration Loader]
        CV[Config Validator]
        CL --> CV
    end

    subgraph Audit["🔍 Environment Audit"]
        EA[Environment Checker]
        JDK[JDK Check]
        MVN[Maven Check]
        LSP[LSP Check]
        LLM[LLM Check]
        EA --> JDK & MVN & LSP & LLM
    end

    subgraph Framework["🚀 Custom Agent Framework"]
        subgraph Adapters["LLM Adapters"]
            OAI[OpenAI Adapter]
            CLA[Claude Adapter]
            GEM[Gemini Adapter]
        end
        subgraph Core["Core Components"]
            CTX[Context Manager<br/>Message History]
            REG[Tool Registry<br/>Reflection-based]
            EXE[Agent Executor<br/>ReAct Loop]
        end
        Adapters --> EXE
        CTX --> EXE
        REG --> EXE
    end

    subgraph Orchestrator["🎯 Agent Orchestrator"]
        SAO[SimpleAgentOrchestrator]
        RE[RetryExecutor<br/>Exponential Backoff]
        SH[StreamingHandler<br/>Real-time Output]
        RT[RepairTracker<br/>Avoid Loops]
        DPB[DynamicPromptBuilder<br/>Context-aware Prompts]
        SAO --> RE & SH & RT & DPB
    end

    subgraph Tools["🛠️ Tool Layer"]
        subgraph FileTools["File Operations"]
            FS[FileSystemTool]
            DIR[DirectoryTool]
        end
        subgraph CodeTools["Code Analysis"]
            CA[CodeAnalyzerTool]
            SC[SyntaxCheckerTool<br/>JavaParser]
            LSPC[LspSyntaxCheckerTool<br/>JDT LS]
        end
        subgraph BuildTools["Build & Test"]
            ME[MavenExecutorTool]
            COV[CoverageTool]
            MUT[MutationTestTool]
        end
        subgraph VCSTools["Version Control"]
            GIT[GitDiffTool]
            PS[ProjectScannerTool]
        end
        subgraph IterTools["Iterative Testing"]
            MIT[MethodIteratorTool]
        end
    end

    CLI --> CL
    CV --> EA
    EA --> SAO
    SAO --> EXE
    EXE --> Tools

    style Input fill:#e1f5fe
    style Config fill:#fff3e0
    style Audit fill:#f3e5f5
    style Framework fill:#e8f5e9
    style Orchestrator fill:#fce4ec
    style Tools fill:#f5f5f5
```

### Component Overview

| Layer | Component | Description |
|-------|-----------|-------------|
| **Input** | CLI | Command-line interface with picocli |
| **Config** | ConfigLoader | YAML config with env variable support |
| **Audit** | EnvironmentChecker | Validates JDK, Maven, LSP, LLM availability |
| **Framework** | Custom Agent Framework | Lightweight ReAct loop with multi-LLM support |
| **Orchestrator** | SimpleAgentOrchestrator | Core loop with retry and streaming |
| **Tools** | 15+ Tools | File, Code, Build, Git, Coverage operations |

### Custom Framework vs LangChain4j

| Aspect | Custom Framework (v1.1.0) | LangChain4j (v1.0.0) |
|--------|---------------------------|----------------------|
| **JAR Size** | ~15MB | ~30MB |
| **Dependencies** | 8 core | 20+ |
| **Zhipu AI** | Full support, fixed 1214 | Partial, message format issues |
| **Context Control** | Fine-grained | Limited |
| **Startup Time** | Faster | Slower |

## Troubleshooting

### Common Issues

#### 1. API Key Not Found

**Error:** `LLM API Key is missing`

**Solution:**
```bash
# Set via environment variable
export UT_AGENT_API_KEY="your-key"

# Or set via config command
java -jar utagent.jar config --api-key "your-key"
```

#### 2. Maven Not Found

**Error:** `Maven not found in PATH`

**Solution:**
- Install Maven 3.8+
- Add Maven to PATH
- Verify: `mvn --version`

#### 3. JaCoCo Report Not Found

**Error:** `JaCoCo coverage report not found`

**Solution:**
```bash
# Ensure JaCoCo is configured in pom.xml
# Run tests with coverage
mvn clean test jacoco:report
```

#### 4. Rate Limit Exceeded (1302 Error)

**Error:** `您当前使用该API的并发数过高`

**Solution:**
- The agent automatically retries with exponential backoff
- Increase `--timeout` and `--max-retries` if needed
- Consider using a higher API tier
- Add delay between requests in config

#### 5. Zhipu AI 1214 Error (Fixed in v1.1.0)

**Error:** `messages 参数非法`

**Solution:**
- Upgrade to v1.1.0
- The custom framework (default since v1.1.0) handles Zhipu AI message format correctly

#### 6. Test Compilation Fails

**Error:** `Compilation failed`

**Solution:**
- The agent will attempt to fix automatically
- Check if all dependencies are in pom.xml
- Verify Mockito version compatibility

### Enable Verbose Logging

```bash
# Via command line
java -jar utagent.jar --target Foo.java -v

# Via system property
java -Dut.agent.log.level=DEBUG -jar utagent.jar --target Foo.java

# Debug custom framework
java -Dut.framework.log.level=DEBUG -jar utagent.jar --target Foo.java
```

### Log Levels

| Level | Content |
|-------|---------|
| `INFO` | User progress, key milestones |
| `DEBUG` | Tool inputs/outputs, technical details |
| `WARN` | Non-fatal issues |
| `ERROR` | Failures requiring attention |

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Development Setup

```bash
# Clone the repo
git clone https://github.com/codelogickeep/unit-test-agent-4j.git
cd unit-test-agent-4j

# Build with tests
mvn clean verify

# Run a specific test
mvn test -Dtest=FileSystemToolTest
```

### Project Structure

```
unit-test-agent-4j/
├── src/main/java/com/codelogickeep/agent/ut/
│   ├── config/           # Configuration loading & validation
│   ├── engine/           # Legacy orchestration (LangChain4j)
│   │   ├── AgentOrchestrator.java
│   │   ├── RetryExecutor.java
│   │   └── EnvironmentChecker.java
│   ├── framework/        # Custom lightweight framework (v1.1.0)
│   │   ├── adapter/      # LLM adapters (OpenAI, Claude, Gemini)
│   │   ├── context/      # Context management
│   │   ├── executor/     # ReAct loop executor
│   │   ├── model/        # Message models
│   │   └── tool/         # Tool registry & execution
│   ├── exception/        # Custom exceptions
│   └── tools/            # Agent tools
│       ├── FileSystemTool.java
│       ├── CodeAnalyzerTool.java
│       ├── CoverageTool.java
│       ├── MethodIteratorTool.java
│       └── ...
├── src/test/java/        # Unit tests
├── doc/                  # Documentation
├── prompts/              # Prompt templates
└── pom.xml
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---

**Made with ❤️ for Java developers tired of writing tests manually.**
