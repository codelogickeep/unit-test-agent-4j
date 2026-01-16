# Unit Test Agent 4j

An AI-powered Java unit test generation agent. Automatically generates high-quality JUnit 5 + Mockito tests for your Java codebase.

[![Java](https://img.shields.io/badge/Java-21+-blue.svg)](https://openjdk.java.net/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-red.svg)](https://maven.apache.org/)
[![Version](https://img.shields.io/badge/Version-2.1.0-green.svg)](https://github.com/codelogickeep/unit-test-agent-4j)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

## Features

- 🤖 **Multi-LLM Support** - OpenAI, Anthropic (Claude), Gemini, Zhipu AI
- 🔧 **Self-Healing** - Auto-fixes compilation errors and test failures
- 📊 **Coverage-Driven** - Analyzes coverage and supplements missing tests
- 🔄 **Iterative Mode** - Generates tests one method at a time
- 📈 **Git Incremental** - Only tests changed files
- ✅ **LSP Syntax Check** - Optional semantic analysis before compile

## Quick Install

### Linux / macOS

```bash
curl -sSL https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/build.sh | bash
export PATH="$PATH:$HOME/.utagent"
```

### Windows (PowerShell 7+)

```powershell
irm https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/build.ps1 | iex
# Add to PATH: $env:USERPROFILE\.utagent
```

## Usage

### 1. Configure API Key

```bash
utagent config --protocol openai --api-key "sk-..." --model "gpt-4o"
```

### 2. Generate Tests

```bash
# Single file
utagent --target src/main/java/com/example/MyService.java

# Batch mode (entire project)
utagent --project /path/to/project

# Incremental mode (Git changes only)
utagent --project /path/to/project --incremental
```

### 3. Common Options

| Option | Description |
|--------|-------------|
| `--target <file>` | Single source file to test |
| `--project <dir>` | Project root for batch mode |
| `--incremental` | Only test Git-changed files |
| `--threshold <n>` | Coverage threshold (default: 80) |
| `--interactive` | Confirm before file writes |
| `-v` | Verbose logging |

## Configuration

Create `agent.yml` in your project root or `~/.utagent/`:

```yaml
llm:
  protocol: "openai"          # openai | anthropic | gemini | openai-zhipu
  apiKey: "sk-..."
  modelName: "gpt-4o"
  temperature: 0.0

workflow:
  coverageThreshold: 80
  use-lsp: false              # Enable LSP for semantic checking
  iterative-mode: false       # Generate tests method by method
```

## Supported LLMs

| Provider | Protocol | Example Model |
|----------|----------|---------------|
| OpenAI | `openai` | gpt-4o, gpt-4-turbo |
| Anthropic | `anthropic` | claude-3-5-sonnet |
| Google | `gemini` | gemini-1.5-pro |
| Zhipu AI | `openai-zhipu` | glm-4 |
| Alibaba | `openai` | qwen-max |

## Requirements

- **JDK 21+**
- **Maven 3.8+**
- **Git**

## License

Apache License 2.0 - See [LICENSE](LICENSE)

---

**Made with ❤️ for Java developers**
