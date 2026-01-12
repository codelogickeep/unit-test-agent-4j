package com.codelogickeep.agent.ut;
import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.engine.AgentOrchestrator;
import com.codelogickeep.agent.ut.engine.LlmClient;
import com.codelogickeep.agent.ut.tools.FileSystemTool;
import com.codelogickeep.agent.ut.tools.MavenExecutorTool;
import com.codelogickeep.agent.ut.tools.ToolFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import com.fasterxml.jackson.annotation.JsonInclude;

import com.codelogickeep.agent.ut.engine.BatchAnalyzer;
import com.codelogickeep.agent.ut.model.TestTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "unit-test-agent", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "AI Agent for generating JUnit 5 tests.",
        subcommands = {App.ConfigCommand.class})
public class App implements Callable<Integer> {

    @Option(names = {"-t", "--target"}, description = "Target Java file to test")
    private String targetFile;

    @Option(names = {"-c", "--config"}, description = "Path to agent configuration file (defaults to config.yml or agent.yml)")
    private String configPath;

    @Option(names = {"--check-env"}, description = "Check environment configuration (Maven, LLM, Permissions) and exit")
    private boolean checkEnv;

    @Option(names = {"-kb", "--knowledge-base"}, description = "Path to existing unit tests (directory or file) to learn coding style and patterns.")
    private String knowledgeBasePath;

    @Option(names = {"--protocol"}, description = "Override LLM Protocol for this run (openai, anthropic, gemini). Does not save to config.")
    private String protocol;

    @Option(names = {"--api-key"}, description = "Override LLM API Key for this run. Does not save to config unless --save is used.")
    private String apiKey;

    @Option(names = {"--base-url"}, description = "Override LLM Base URL for this run. Does not save to config unless --save is used.")
    private String baseUrl;

    @Option(names = {"--model"}, description = "Override LLM Model Name for this run. Does not save to config unless --save is used.")
    private String modelName;

    @Option(names = {"--temperature"}, description = "Override LLM Temperature for this run. Does not save to config.")
    private Double temperature;

    @Option(names = {"--max-retries"}, description = "Override Max Retries for this run. Does not save to config.")
    private Integer maxRetries;

    @Option(names = {"--save"}, description = "Save the overridden configuration (API Key, Base URL, Model) to agent.yml for future runs.")
    private boolean saveConfig;

    @Option(names = {"-i", "--interactive"}, description = "Enable interactive mode: confirm before writing test files.")
    private boolean interactive;

    @Option(names = {"-p", "--project"}, description = "Project directory for batch mode: scan and generate tests for all uncovered classes.")
    private String projectDir;

    @Option(names = {"--exclude"}, description = "Exclude patterns for batch mode (comma-separated globs, e.g., '**/dto/*.java,**/vo/*.java').")
    private String excludePatterns;

    @Option(names = {"--dry-run"}, description = "Batch mode: analyze only, print report without generating tests.")
    private boolean dryRun;

    @Option(names = {"--threshold"}, description = "Coverage threshold (0-100). Methods below this threshold will be targeted. Default: 80.")
    private Integer coverageThreshold;

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Unit Test Agent: An AI assistant for automatically generating JUnit 5 unit tests.");
            System.out.println();
            new CommandLine(new App()).usage(System.out);
            System.exit(0);
        }
        int exitCode = new CommandLine(new App()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        // 1. Load Configurations
        AppConfig config = loadAppConfig();

        // 1.1 Apply CLI Overrides
        if (config.getLlm() == null) {
            config.setLlm(new AppConfig.LlmConfig());
        }
        if (protocol != null) {
            config.getLlm().setProtocol(protocol);
        }
        if (apiKey != null) {
            config.getLlm().setApiKey(apiKey);
        }
        if (baseUrl != null) {
            config.getLlm().setBaseUrl(baseUrl);
        }
        if (modelName != null) {
            config.getLlm().setModelName(modelName);
        }
        if (temperature != null) {
            config.getLlm().setTemperature(temperature);
        }
        
        if (config.getWorkflow() == null) {
            config.setWorkflow(new AppConfig.WorkflowConfig());
        }
        if (maxRetries != null) {
            config.getWorkflow().setMaxRetries(maxRetries);
        }
        if (interactive) {
            config.getWorkflow().setInteractive(true);
        }

        // Apply Defaults if still null
        boolean needsSave = false;
        if (config.getLlm().getTemperature() == null) {
            config.getLlm().setTemperature(0.1);
            needsSave = true;
        }
        if (config.getWorkflow() == null) {
            config.setWorkflow(new AppConfig.WorkflowConfig());
            needsSave = true;
        }
        if (config.getWorkflow().getMaxRetries() <= 0 && maxRetries == null) {
            config.getWorkflow().setMaxRetries(3);
            needsSave = true;
        }

        // 1.2 Save Config if requested or if defaults were applied to a non-existent config
        if (saveConfig || (needsSave && !new File(getJarDir(), "agent.yml").exists())) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            File configFile = new File(getJarDir(), "agent.yml");
            try {
                mapper.writeValue(configFile, config);
                System.out.println(">>> Configuration updated with defaults and saved to " + configFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Warning: Failed to save configuration: " + e.getMessage());
            }
        }

        if (checkEnv) {
            String projectRoot = targetFile != null ? detectProjectRoot(targetFile) : null;
            com.codelogickeep.agent.ut.engine.EnvironmentChecker.check(config, projectRoot);
            return 0;
        }
        
        // 2. Setup Context
        // Governance context removed.

        try {
            // 3. Initialize Tools Dynamically
            List<Object> tools = ToolFactory.loadAndWrapTools(config, knowledgeBasePath);

            // 4. Initialize AI Engine
            LlmClient llmClient = new LlmClient(config.getLlm());
            
            // Detect Project Root (Directory containing pom.xml near target file)
            // If --project is specified, use it as the project root
            String projectRoot;
            if (projectDir != null) {
                projectRoot = new File(projectDir).getAbsolutePath();
            } else {
                projectRoot = detectProjectRoot(targetFile);
            }
            
            // Perform Project Audit at startup
            com.codelogickeep.agent.ut.engine.EnvironmentChecker.check(config, projectRoot);
            
            for (Object tool : tools) {
                if (tool instanceof FileSystemTool) {
                    ((FileSystemTool) tool).setProjectRoot(projectRoot);
                    ((FileSystemTool) tool).setInteractive(config.getWorkflow().isInteractive());
                }
                if (tool instanceof MavenExecutorTool) {
                    ((MavenExecutorTool) tool).setProjectRoot(projectRoot);
                }
            }

            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    config,
                    llmClient.createStreamingModel(),
                    tools
            );

            // 5. Run Agent
            if (projectDir != null && targetFile != null) {
                // Single file mode with explicit project directory
                // targetFile is relative to projectDir
                String absoluteTargetPath = new File(projectRoot, targetFile).getAbsolutePath();
                System.out.println(">>> Agent started for target: " + absoluteTargetPath);
                System.out.println(">>> Project root: " + projectRoot);
                orchestrator.run(targetFile);
                System.out.println(">>> Agent finished.");
            } else if (projectDir != null) {
                // Batch mode
                return runBatchMode(config, llmClient, tools, projectRoot);
            } else if (targetFile != null) {
                // Single file mode
                System.out.println(">>> Agent started for target: " + targetFile);
                orchestrator.run(targetFile);
                System.out.println(">>> Agent finished.");
            } else {
                System.err.println("Error: Missing required option: --target=<targetFile> or --project=<projectDir>");
                return 1;
            }
            
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("Configuration Error: " + e.getMessage());
            System.err.println();
            new CommandLine(this).usage(System.err);
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private String detectProjectRoot(String targetFilePath) {
        if (targetFilePath == null) return ".";
        File file = new File(targetFilePath).getAbsoluteFile();
        File current = file.isDirectory() ? file : file.getParentFile();
        
        while (current != null) {
            if (new File(current, "pom.xml").exists()) {
                return current.getAbsolutePath();
            }
            current = current.getParentFile();
        }
        return ".";
    }

    private static File getJarDir() {
        try {
            return new File(App.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
        } catch (Exception e) {
            return new File(".");
        }
    }

    private AppConfig loadAppConfig() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        AppConfig config = new AppConfig();

        // 1. Classpath (agent.yml) - Base defaults
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("agent.yml")) {
            if (in != null) {
                mapper.readerForUpdating(config).readValue(in);
            }
        }

        // 2. User Home (~/.unit-test-agent/)
        String userHome = System.getProperty("user.home");
        mergeConfigFromFile(mapper, config, Paths.get(userHome, ".unit-test-agent", "config.yml").toFile());
        mergeConfigFromFile(mapper, config, Paths.get(userHome, ".unit-test-agent", "agent.yml").toFile());

        // 3. Current Directory (config.yml or agent.yml)
        mergeConfigFromFile(mapper, config, new File("config.yml"));
        mergeConfigFromFile(mapper, config, new File("agent.yml"));

        // 4. JAR Directory (agent.yml) - Priority for global config
        mergeConfigFromFile(mapper, config, new File(getJarDir(), "agent.yml"));

        // 5. CLI Path - Highest priority
        if (configPath != null) {
            mergeConfigFromFile(mapper, config, new File(configPath));
        }

        // Environment variable substitution
        if (config.getLlm() != null) {
            config.getLlm().setProtocol(replaceEnvVars(config.getLlm().getProtocol()));
            config.getLlm().setApiKey(replaceEnvVars(config.getLlm().getApiKey()));
            config.getLlm().setBaseUrl(replaceEnvVars(config.getLlm().getBaseUrl()));
            config.getLlm().setModelName(replaceEnvVars(config.getLlm().getModelName()));
        }

        return config;
    }

    private void mergeConfigFromFile(ObjectMapper mapper, AppConfig config, File file) {
        if (file.exists()) {
            try {
                mapper.readerForUpdating(config).readValue(file);
                System.out.println(">>> Merged configuration from " + file.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Warning: Failed to merge config from " + file.getAbsolutePath() + ": " + e.getMessage());
            }
        }
    }

    private String replaceEnvVars(String value) {
        if (value == null || !value.contains("${env:")) {
            return value;
        }
        
        // Simple regex-less replacement for multiple env vars if needed, 
        // but current logic assumes the whole string might be an env var placeholder
        if (value.startsWith("${env:") && value.endsWith("}")) {
            String envVar = value.substring(6, value.length() - 1);
            String envValue = System.getenv(envVar);
            return envValue != null ? envValue : value;
        }
        
        return value;
    }

    private int runBatchMode(AppConfig config, LlmClient llmClient, List<Object> tools, String projectRoot) {
        System.out.println(">>> Batch mode started for project: " + projectRoot);

        int threshold = coverageThreshold != null ? coverageThreshold : 80;
        BatchAnalyzer analyzer = new BatchAnalyzer(projectRoot, threshold);

        try {
            List<TestTask> tasks = analyzer.analyze(excludePatterns);

            if (tasks.isEmpty()) {
                System.out.println(">>> No classes need test generation.");
                return 0;
            }

            if (dryRun) {
                analyzer.printReport(tasks);
                return 0;
            }

            System.out.println(">>> Found " + tasks.size() + " classes needing tests:");
            for (TestTask t : tasks) {
                System.out.println("    - " + t.getSourceFilePath() + " (" + t.getUncoveredMethods().size() + " uncovered methods)");
            }

            // Process each class
            int processed = 0;
            for (TestTask task : tasks) {
                System.out.println("\n>>> Processing [" + (++processed) + "/" + tasks.size() + "]: " + task.getSourceFilePath());
                try {
                    AgentOrchestrator orchestrator = new AgentOrchestrator(
                            config, llmClient.createStreamingModel(), tools
                    );
                    // Pass task context to orchestrator
                    String taskPrompt = analyzer.buildTaskPrompt(task);
                    orchestrator.run(task.getSourceFilePath(), taskPrompt);
                } catch (Exception e) {
                    System.err.println("    Error: " + e.getMessage());
                }
            }

            System.out.println("\n>>> Batch mode completed. Processed " + processed + " classes.");
            return 0;
        } catch (Exception e) {
            System.err.println("Error during batch analysis: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    @Command(name = "config", 
            mixinStandardHelpOptions = true,
            description = "Configure and persist agent settings to agent.yml.",
            header = "Agent Configuration Utility",
            optionListHeading = "%nOptions:%n")
    public static class ConfigCommand implements Callable<Integer> {

        @Option(names = {"--protocol"}, description = "Set the LLM Protocol. Supported: openai, anthropic, gemini.")
        private String protocol;

        @Option(names = {"--api-key"}, description = "Set the LLM API Key for authentication.")
        private String apiKey;

        @Option(names = {"--base-url"}, description = "Set the LLM Base URL (e.g., https://api.openai.com/v1).")
        private String baseUrl;

        @Option(names = {"--model"}, description = "Set the LLM Model Name (e.g., gpt-4, gemini-pro).")
        private String modelName;

        @Option(names = {"--temperature"}, description = "Set the LLM sampling temperature (0.0 to 1.0).")
        private Double temperature;

        @Option(names = {"--max-retries"}, description = "Set the maximum number of retries for the workflow.")
        private Integer maxRetries;

        @Override
        public Integer call() throws Exception {
            File configFile = new File(getJarDir(), "agent.yml");
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

            AppConfig config = null;
            boolean isNew = !configFile.exists();
            if (configFile.exists()) {
                try {
                    config = mapper.readValue(configFile, AppConfig.class);
                } catch (IOException e) {
                    System.err.println("Warning: Failed to parse existing agent.yml, creating new one.");
                }
            }

            if (config == null) {
                config = new AppConfig();
                isNew = true;
            }
            if (config.getLlm() == null) {
                config.setLlm(new AppConfig.LlmConfig());
            }

            boolean changed = false;
            if (protocol != null) {
                config.getLlm().setProtocol(protocol);
                changed = true;
            }
            if (apiKey != null) {
                config.getLlm().setApiKey(apiKey);
                changed = true;
            }
            if (baseUrl != null) {
                config.getLlm().setBaseUrl(baseUrl);
                changed = true;
            }
            if (modelName != null) {
                config.getLlm().setModelName(modelName);
                changed = true;
            }
            if (temperature != null) {
                config.getLlm().setTemperature(temperature);
                changed = true;
            }

            if (config.getWorkflow() == null) {
                config.setWorkflow(new AppConfig.WorkflowConfig());
            }
            if (maxRetries != null) {
                config.getWorkflow().setMaxRetries(maxRetries);
                changed = true;
            }

            if (changed || isNew) {
                mapper.writeValue(configFile, config);
                if (isNew) {
                    System.out.println("Initialized new configuration at " + configFile.getAbsolutePath());
                } else {
                    System.out.println("Configuration saved to " + configFile.getAbsolutePath());
                }
            } else {
                System.out.println("Current configuration (" + configFile.getAbsolutePath() + "):");
                System.out.println(mapper.writeValueAsString(config));
                System.out.println();
                System.out.println("No changes specified. Use --api-key, --base-url, or --model options to update.");
            }
            return 0;
        }
    }
}

