# Unit Test Agent 4j

An AI-powered Java unit test generation agent. Automatically generates high-quality JUnit 5 + Mockito tests.

[![Java](https://img.shields.io/badge/Java-21+-blue.svg)](https://openjdk.java.net/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-red.svg)](https://maven.apache.org/)
[![Version](https://img.shields.io/badge/Version-2.2.0-green.svg)](https://github.com/codelogickeep/unit-test-agent-4j)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

## Features

- 🤖 **Multi-LLM Support** - OpenAI, Anthropic, Gemini, Zhipu AI
- 🔧 **Self-Healing** - Auto-fixes compilation errors and test failures
- 📊 **Coverage-Driven** - Analyzes coverage and supplements missing tests
- 🔄 **Iterative Mode** - Generates tests one method at a time
- ✅ **LSP Syntax Check** - Optional semantic analysis before compile
- 🔄 **Dynamic Phase Switching** - Saves 40-60% tokens (v2.2.0)

## Quick Install

### Linux / macOS

```bash
curl -sSL https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/build.sh | bash
export PATH="$PATH:$HOME/.utagent"
```

### Windows (PowerShell 7+)

```powershell
irm https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/build.ps1 | iex
```

---

## Workflow

```
┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│  Analysis   │ → │ Generation  │ → │Verification │ → │   Repair    │
│ Read source │   │ Write tests │   │Compile/Test │   │ Fix errors  │
└─────────────┘   └─────────────┘   └─────────────┘   └─────────────┘
                                            │                │
                                            └────────────────┘
                                              Loop until pass
```

1. **Analysis** - Read and analyze source code (AST, dependencies, complexity)
2. **Generation** - Generate test code based on patterns and knowledge base
3. **Verification** - Compile, run tests, check coverage
4. **Repair** - Auto-fix failures, repeat until success or max retries

---

## Usage Examples

### 1. Configure API Key (First Time)

```bash
# Interactive config
utagent config --protocol openai --api-key "sk-..." --model "gpt-4o"

# Or use environment variables
export UT_AGENT_API_KEY="sk-..."
export UT_AGENT_MODEL_NAME="gpt-4o"
```

### 2. Single File Mode

```bash
# Basic
utagent --target src/main/java/com/example/MyService.java

# With knowledge base (learn from existing tests)
utagent --target src/main/java/com/example/MyService.java \
  -kb src/test/java

# Interactive mode (confirm before writes)
utagent --target src/main/java/com/example/MyService.java -i

# Custom coverage threshold
utagent --target src/main/java/com/example/MyService.java --threshold 90
```

### 3. Batch Mode (Entire Project)

```bash
# Scan and test all uncovered classes
utagent --project /path/to/project

# With exclusions
utagent --project /path/to/project \
  --exclude "**/dto/**,**/vo/**,**/entity/**"

# Dry-run (analyze only)
utagent --project /path/to/project --dry-run
```

---

## Command Line Reference

### Main Options

| Option | Short | Description | Default |
|--------|-------|-------------|---------|
| `--target <file>` | `-t` | Target Java source file | - |
| `--project <dir>` | `-p` | Project root for batch mode | - |
| `--config <file>` | `-c` | Config file path | auto-detect |
| `--threshold <n>` | | Coverage threshold (0-100) | 80 |
| `--interactive` | `-i` | Confirm before file writes | false |
| `--dry-run` | | Analyze only, no generation | false |
| `--check-env` | | Check environment and exit | - |
| `-v` | | Verbose logging | false |
| `-h, --help` | | Show help | - |

### LLM Override Options

| Option | Description | Example |
|--------|-------------|---------|
| `--protocol` | LLM protocol | `openai`, `anthropic`, `gemini`, `openai-zhipu` |
| `--api-key` | API key | `sk-...` |
| `--base-url` | API base URL | `https://api.openai.com` |
| `--model` | Model name | `gpt-4o`, `claude-3-5-sonnet` |
| `--temperature` | Sampling temperature | `0.0` - `1.0` |
| `--max-retries` | Max retry attempts | `5` |
| `--save` | Save overrides to config | - |

### Batch Mode Options

| Option | Description | Example |
|--------|-------------|---------|
| `--exclude` | Exclusion patterns (comma-separated) | `**/dto/**,**/vo/**` |
| `--dry-run` | Analyze only | - |

### Knowledge Base Options

| Option | Short | Description |
|--------|-------|-------------|
| `--knowledge-base` | `-kb` | Path to existing tests for style learning |

---

## Configuration File (`agent.yml`)

Create in project root, JAR directory, or `~/.utagent/`:

```yaml
# =============================================================================
# LLM Settings
# =============================================================================
llm:
  protocol: "openai"                      # openai | openai-zhipu | anthropic | gemini
  api-key: "${env:UT_AGENT_API_KEY}"       # Supports environment variables
  base-url: "${env:UT_AGENT_BASE_URL}"     # Optional, protocol default used
  model-name: "gpt-4o"                     # Model name
  temperature: 0.0                        # 0.0 (precise) ~ 1.0 (creative)
  timeout: 120                            # Request timeout (seconds)
  custom-headers: {}                       # Custom HTTP headers

# =============================================================================
# Workflow Settings
# =============================================================================
workflow:
  max-retries: 5                           # Max retry on failure
  coverage-threshold: 80                   # Target coverage (%)
  interactive: false                      # Confirm before writes
  use-lsp: false                          # Enable LSP syntax check
  iterative-mode: false                   # Per-method test generation
  method-coverage-threshold: 80           # Per-method coverage threshold
  skip-low-priority: false                # Skip getters/setters
  max-stale-iterations: 3                 # Stop after N iterations without progress
  min-coverage-gain: 1                    # Min coverage gain (%) per iteration
  enable-phase-switching: false           # Dynamic phase switching (40-60% token savings)

# =============================================================================
# Batch Mode Settings
# =============================================================================
batch:
  exclude-patterns: ""                     # Glob patterns to exclude
  dry-run: false                           # Analyze only
  
# =============================================================================
# Incremental Mode Settings
# =============================================================================
incremental:
  mode: "uncommitted"                     # uncommitted | staged | compare
  target-ref: "HEAD"                       # Target Git ref

# =============================================================================
# Recommended Dependencies (for environment check)
# =============================================================================
dependencies:
  junit-jupiter: "5.10.1"
  mockito-core: "5.8.0"
  mockito-junit-jupiter: "5.8.0"
  mockito-inline: "5.8.0"
  jacoco-maven-plugin: "0.8.11"
```

---

## Configuration Options Reference

### LLM Settings (`llm`)

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `protocol` | string | `"openai"` | LLM provider protocol |
| `api-key` | string | - | API key (supports `${env:VAR}`) |
| `base-url` | string | - | API base URL |
| `model-name` | string | - | Model name |
| `temperature` | float | `0.0` | Sampling temperature |
| `timeout` | int | `120` | Request timeout (seconds) |
| `custom-headers` | map | `{}` | Custom HTTP headers |

### Workflow Settings (`workflow`)

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `max-retries` | int | `5` | Max retry attempts |
| `coverage-threshold` | int | `80` | Target coverage % |
| `interactive` | bool | `false` | Confirm before writes |
| `use-lsp` | bool | `false` | Enable LSP syntax checking |
| `iterative-mode` | bool | `false` | Per-method generation |
| `method-coverage-threshold` | int | `80` | Per-method coverage % |
| `skip-low-priority` | bool | `false` | Skip getters/setters |
| `max-stale-iterations` | int | `3` | Stop after N no-progress iterations |
| `min-coverage-gain` | int | `1` | Min coverage gain % per iteration |
| `enable-phase-switching` | bool | `false` | Enable dynamic phase switching (saves 40-60% tokens) |

### Batch Settings (`batch`)

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `exclude-patterns` | string | `""` | Comma-separated glob patterns |
| `dry-run` | bool | `false` | Analyze only mode |

### Incremental Settings (`incremental`)

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `mode` | string | `"uncommitted"` | `uncommitted` / `staged` / `compare` |
| `base-ref` | string | - | Base Git ref (for compare mode) |
| `target-ref` | string | `"HEAD"` | Target Git ref |

---

## Supported LLMs

| Provider | Protocol | Example Models | Base URL |
|----------|----------|----------------|----------|
| OpenAI | `openai` | gpt-4o, gpt-4-turbo | https://api.openai.com |
| Anthropic | `anthropic` | claude-3-5-sonnet | https://api.anthropic.com |
| Google | `gemini` | gemini-1.5-pro | https://generativelanguage.googleapis.com |
| Zhipu AI | `openai-zhipu` | glm-4, glm-4.7 | https://open.bigmodel.cn/api/coding/paas/v4 |
| Alibaba | `openai` | qwen-max | https://dashscope.aliyuncs.com/compatible-mode/v1 |

---

## Environment Variables

| Variable | Description |
|----------|-------------|
| `UT_AGENT_API_KEY` | LLM API key |
| `UT_AGENT_BASE_URL` | LLM API base URL |
| `UT_AGENT_MODEL_NAME` | Model name |

---

## Requirements

- **JDK 21+**
- **Maven 3.8+**
- **Git**

## License

Apache License 2.0 - See [LICENSE](LICENSE)

---

**Made with ❤️ for Java developers**
