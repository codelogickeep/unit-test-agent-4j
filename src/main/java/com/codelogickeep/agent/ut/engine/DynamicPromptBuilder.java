package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.tools.StyleAnalyzerTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Builds dynamic system prompts based on project analysis and configuration.
 * Combines base prompt template with project-specific style guidelines.
 */
public class DynamicPromptBuilder {
    private static final Logger log = LoggerFactory.getLogger(DynamicPromptBuilder.class);

    private final AppConfig config;
    private final StyleAnalyzerTool styleAnalyzer;
    private String cachedProjectStyle = null;
    private String cachedProjectPath = null;

    public DynamicPromptBuilder(AppConfig config) {
        this.config = config;
        this.styleAnalyzer = new StyleAnalyzerTool();
    }

    /**
     * Builds the complete system prompt for the Agent.
     * Combines base template with project-specific context.
     *
     * @param projectPath Path to the project being tested
     * @return Complete system prompt
     */
    public String buildSystemPrompt(String projectPath) {
        log.info("Building dynamic system prompt for project: {}", projectPath);

        StringBuilder prompt = new StringBuilder();

        // 1. Load base prompt template
        String basePrompt = loadBasePrompt();
        prompt.append(basePrompt);

        // 2. Add project style context if available
        String styleContext = getProjectStyleContext(projectPath);
        if (styleContext != null && !styleContext.isEmpty()) {
            prompt.append("\n\n");
            prompt.append("## PROJECT-SPECIFIC TESTING CONVENTIONS\n\n");
            prompt.append("Based on analysis of existing tests in this project, follow these conventions:\n\n");
            prompt.append(styleContext);
        }

        // 3. Add configuration-based context
        String configContext = buildConfigContext();
        if (configContext != null && !configContext.isEmpty()) {
            prompt.append("\n\n");
            prompt.append("## CONFIGURATION CONTEXT\n\n");
            prompt.append(configContext);
        }

        log.info("Built system prompt with {} characters", prompt.length());
        return prompt.toString();
    }

    /**
     * Gets project style context, using cache if available.
     */
    private String getProjectStyleContext(String projectPath) {
        if (projectPath == null) {
            return null;
        }

        // Use cached style if same project
        if (projectPath.equals(cachedProjectPath) && cachedProjectStyle != null) {
            log.debug("Using cached project style for: {}", projectPath);
            return cachedProjectStyle;
        }

        try {
            // Try to get style guidelines
            String guidelines = styleAnalyzer.getTestStyleGuidelines(projectPath);
            if (guidelines != null && !guidelines.contains("No existing tests found")) {
                cachedProjectStyle = guidelines;
                cachedProjectPath = projectPath;
                return guidelines;
            }
        } catch (IOException e) {
            log.warn("Failed to analyze project style: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Builds context from configuration settings.
     */
    private String buildConfigContext() {
        if (config == null || config.getWorkflow() == null) {
            return null;
        }

        StringBuilder context = new StringBuilder();
        AppConfig.WorkflowConfig workflow = config.getWorkflow();

        context.append("- Target coverage threshold: ").append(workflow.getCoverageThreshold()).append("%\n");
        context.append("- Maximum retry attempts: ").append(workflow.getMaxRetries()).append("\n");

        if (workflow.isInteractive()) {
            context.append("- Interactive mode: Enabled (user will confirm file writes)\n");
        }

        // 当 use-lsp 启用时，强制使用 LSP 进行语法检查
        if (workflow.isUseLsp()) {
            context.append("\n### LSP SYNTAX CHECK (MANDATORY)\n");
            context.append("**IMPORTANT**: LSP is ENABLED and auto-initialized for this project.\n\n");
            context.append("**YOU MUST USE LSP FOR ALL SYNTAX CHECKS:**\n");
            context.append("1. Use `checkSyntaxWithLsp(filePath)` to check Java files AFTER writing them\n");
            context.append("2. Use `checkContentWithLsp(content, targetPath)` to check code BEFORE writing to file\n");
            context.append("3. LSP detects: type errors, missing imports, undefined methods, wrong signatures\n");
            context.append("4. **DO NOT** use `checkSyntax` or `checkSyntaxContent` (JavaParser is insufficient)\n\n");
            context.append("**WORKFLOW WITH LSP:**\n");
            context.append("- Before writing test file: `checkContentWithLsp(testCode, testFilePath)` to validate\n");
            context.append("- After writing test file: `checkSyntaxWithLsp(testFilePath)` to confirm no errors\n");
            context.append("- Fix any LSP_ERRORS before running tests\n");
        }

        return context.toString();
    }

    /**
     * Loads the base prompt template from configuration or default.
     */
    private String loadBasePrompt() {
        String defaultPrompt = getDefaultPrompt();

        if (config == null || config.getPrompts() == null || !config.getPrompts().containsKey("system")) {
            log.info("Using default system prompt");
            return defaultPrompt;
        }

        String pathStr = config.getPrompts().get("system");
        log.info("Loading system prompt from: {}", pathStr);

        try {
            // 1. Try file system relative to working directory
            Path path = Paths.get(pathStr);
            if (Files.exists(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }

            // 2. Try classpath
            String resourcePath = pathStr.startsWith("/") ? pathStr.substring(1) : pathStr;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (in != null) {
                    log.info("Loaded system prompt from classpath: {}", resourcePath);
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }

            log.warn("System prompt file not found at: {}. Using default.", pathStr);
        } catch (IOException e) {
            log.warn("Failed to load system prompt from {}: {}", pathStr, e.getMessage());
        }

        return defaultPrompt;
    }

    /**
     * Returns the default hardcoded prompt.
     */
    private String getDefaultPrompt() {
        return """
            You are an expert Java QA Engineer specializing in unit testing.
            
            ## YOUR ROLE
            You analyze Java code and generate high-quality JUnit 5 + Mockito tests.
            You use the provided tools to read source code, analyze classes, write test files, and verify them.
            
            ## TESTING STANDARDS
            - Use JUnit 5 with @ExtendWith(MockitoExtension.class)
            - Use @Mock for dependencies and @InjectMocks for the class under test
            - Follow the AAA pattern: Arrange, Act, Assert
            - Test both happy paths and edge cases
            - Include meaningful @DisplayName annotations
            - Aim for high line and branch coverage
            
            ## WORKFLOW
            1. Read and analyze the target source file
            2. Identify the class structure, dependencies, and methods
            3. Search knowledge base for similar test patterns (if available)
            4. Generate comprehensive test class
            5. Write the test file
            6. Compile and run the tests
            7. Fix any failures and verify coverage
            
            ## IMPORTANT RULES
            - Always use relative paths from the project root
            - Never modify the source code being tested
            - Handle exceptions appropriately in tests
            - Use verify() to check mock interactions when relevant
            """;
    }

    /**
     * Clears the cached project style.
     * Call this when switching to a different project.
     */
    public void clearCache() {
        cachedProjectStyle = null;
        cachedProjectPath = null;
        log.debug("Cleared project style cache");
    }

    /**
     * Gets the style analyzer for direct use.
     */
    public StyleAnalyzerTool getStyleAnalyzer() {
        return styleAnalyzer;
    }
}
