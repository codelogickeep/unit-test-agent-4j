package com.codelogickeep.agent.ut;
import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.engine.AgentOrchestrator;
import com.codelogickeep.agent.ut.engine.LlmClient;
import com.codelogickeep.agent.ut.tools.ToolFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "unit-test-agent", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "AI Agent for generating JUnit 5 tests.")
public class App implements Callable<Integer> {

    @Option(names = {"-t", "--target"}, description = "Target Java file to test")
    private String targetFile;

    @Option(names = {"-c", "--config"}, description = "Path to agent configuration file (defaults to config.yml or agent.yml)")
    private String configPath;

    @Option(names = {"--check-env"}, description = "Check environment configuration (Maven, LLM, Permissions) and exit")
    private boolean checkEnv;

    @Option(names = {"-kb", "--knowledge-base"}, description = "Path to existing unit tests (directory or file) to learn coding style and patterns.")
    private String knowledgeBasePath;

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

        if (checkEnv) {
            com.codelogickeep.agent.ut.engine.EnvironmentChecker.check(config);
            return 0;
        }
        
        // 2. Setup Context
        // Governance context removed.

        try {
            // 3. Initialize Tools Dynamically
            List<Object> tools = ToolFactory.loadAndWrapTools(config, knowledgeBasePath);

            // 4. Initialize AI Engine
            LlmClient llmClient = new LlmClient(config.getLlm());
            
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    config,
                    llmClient.createStreamingModel(),
                    tools
            );

            // 5. Run Agent
            if (targetFile == null) {
                System.err.println("Error: Missing required option: --target=<targetFile>");
                return 1;
            }
            System.out.println(">>> Agent started for target: " + targetFile);
            orchestrator.run(targetFile);
            System.out.println(">>> Agent finished.");
            
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private AppConfig loadAppConfig() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        AppConfig config = null;

        // 1. CLI Path
        if (configPath != null) {
            File f = new File(configPath);
            if (f.exists()) {
                config = mapper.readValue(f, AppConfig.class);
            }
        }

        // 2. Current Directory (config.yml or agent.yml)
        if (config == null) {
            config = tryLoad(mapper, new File("config.yml"), AppConfig.class);
        }
        if (config == null) {
            config = tryLoad(mapper, new File("agent.yml"), AppConfig.class);
        }

        // 3. User Home (~/.unit-test-agent/)
        if (config == null) {
            String userHome = System.getProperty("user.home");
            config = tryLoad(mapper, Paths.get(userHome, ".unit-test-agent", "config.yml").toFile(), AppConfig.class);
        }
        if (config == null) {
            String userHome = System.getProperty("user.home");
            config = tryLoad(mapper, Paths.get(userHome, ".unit-test-agent", "agent.yml").toFile(), AppConfig.class);
        }

        // 4. Classpath (agent.yml)
        if (config == null) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("agent.yml")) {
                if (in != null) {
                    config = mapper.readValue(in, AppConfig.class);
                }
            }
        }

        if (config == null) {
            throw new IOException("Could not find agent configuration file.");
        }

        // Environment variable substitution
        if (config.getLlm() != null && config.getLlm().getApiKey() != null 
                && config.getLlm().getApiKey().startsWith("${env:")) {
            String envVar = config.getLlm().getApiKey().substring(6, config.getLlm().getApiKey().length() - 1);
            String value = System.getenv(envVar);
            if (value != null) {
                config.getLlm().setApiKey(value);
            }
        }

        return config;
    }

    private <T> T tryLoad(ObjectMapper mapper, File file, Class<T> clazz) {
        if (file.exists()) {
            try {
                return mapper.readValue(file, clazz);
            } catch (IOException e) {
                System.err.println("Warning: Failed to read config from " + file.getAbsolutePath() + ": " + e.getMessage());
            }
        }
        return null;
    }
}
