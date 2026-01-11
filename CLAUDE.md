# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language Preference

**Use Chinese (中文) for all communication with the user.** Code comments and technical documentation may remain in English.

## Project Overview

Unit Test Agent 4j is an AI-powered Java tool that automatically generates JUnit 5 + Mockito unit tests for legacy systems. It uses LangChain4j to integrate with multiple LLM providers (OpenAI, Anthropic, Gemini) and includes self-healing capabilities.

## Build & Run Commands

```bash
# Build the project (creates shaded JAR)
mvn clean package

# Run tests for this project
mvn test

# Build native image (requires GraalVM)
mvn -Pnative package

# Run the agent (single file mode)
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar --target path/to/Class.java

# Run in batch mode (scan entire project)
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar --project /path/to/project --exclude "**/dto/**"

# Configure LLM settings (saved to agent.yml)
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar config --protocol openai --api-key "sk-..." --model "gpt-4"

# Check environment (Maven, dependencies, permissions)
java -jar target/unit-test-agent-4j-0.1.0-LITE-shaded.jar --check-env
```

## Architecture

The project follows an **Agent-Tool architecture**:

- **Agent Layer** (`engine/`): Handles reasoning, orchestration, and LLM interaction via LangChain4j
  - `AgentOrchestrator`: Main workflow with retry loop and exponential backoff
  - `LlmClient`: Factory for creating streaming chat models
  - `EnvironmentChecker`: Validates project dependencies and versions
  - `BatchAnalyzer`: Pre-analyzes projects for coverage-driven testing

- **Infrastructure/Tool Layer** (`tools/`): Executable tools that the Agent calls
  - All tools implement `AgentTool` marker interface
  - `CodeAnalyzerTool`: AST parsing via JavaParser
  - `CoverageTool`: JaCoCo XML report parsing
  - `FileSystemTool`: File I/O with project root protection
  - `MavenExecutorTool`: Runs `mvn` commands (supports PowerShell 7 on Windows)
  - `KnowledgeBaseTool`: RAG-style search of existing test patterns
  - `ProjectScannerTool`: Discovers Java source files
  - `TestDiscoveryTool`: Finds existing test files

## Key Design Patterns

1. **Tool Registration**: `ToolFactory.loadAndWrapTools()` dynamically discovers all `AgentTool` implementations via reflection and wraps them for LangChain4j

2. **Configuration Layering**: `agent.yml` is loaded from multiple locations (classpath → user home → current dir → JAR dir → CLI path), with later sources overriding earlier ones. Environment variables are supported via `${env:VAR_NAME}` syntax.

3. **Project Root Protection**: `FileSystemTool` enforces that all file operations are relative to the detected project root (directory containing `pom.xml`). This prevents path traversal issues.

4. **Self-Healing Loop**: `AgentOrchestrator` wraps LLM calls in a retry loop with exponential backoff (2^attempt * 1000ms). Failed generations are automatically re-attempted up to `maxRetries`.

5. **Coverage-Driven Testing**: Batch mode uses JaCoCo reports to identify uncovered methods, then generates tests only for what's needed rather than re-analyzing everything.

## Configuration

Default configuration is in `src/main/resources/agent.yml`. Runtime configuration is loaded from:
- `~/.unit-test-agent/agent.yml` (user home)
- `./agent.yml` (current directory)
- JAR directory (recommended for distribution)

CLI arguments override config file values but don't persist unless `--save` is used.

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
