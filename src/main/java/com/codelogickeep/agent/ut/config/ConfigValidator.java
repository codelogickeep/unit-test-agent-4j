package com.codelogickeep.agent.ut.config;

import com.codelogickeep.agent.ut.exception.AgentToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates AppConfig and provides intelligent defaults.
 */
public class ConfigValidator {
    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);

    // Default values (must match agent.yml)
    private static final double DEFAULT_TEMPERATURE = 0.3;
    private static final long DEFAULT_TIMEOUT = 120L;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_COVERAGE_THRESHOLD = 80;

    /**
     * Validates the configuration and throws exception if invalid.
     *
     * @param config the configuration to validate
     * @throws AgentToolException if required fields are missing
     */
    public static void validate(AppConfig config) {
        List<String> errors = new ArrayList<>();

        if (config == null) {
            throw new AgentToolException(
                    AgentToolException.ErrorCode.CONFIG_INVALID,
                    "Configuration is null",
                    "No configuration loaded"
            );
        }

        // Validate LLM config (required)
        if (config.getLlm() == null) {
            errors.add("llm: LLM configuration is required");
        } else {
            validateLlmConfig(config.getLlm(), errors);
        }

        if (!errors.isEmpty()) {
            String errorMessage = "Configuration validation failed:\n  - " + String.join("\n  - ", errors);
            throw new AgentToolException(
                    AgentToolException.ErrorCode.CONFIG_MISSING_FIELD,
                    errorMessage,
                    "Check your agent.yml or command line parameters"
            );
        }

        log.info("Configuration validation passed");
    }

    private static void validateLlmConfig(AppConfig.LlmConfig llm, List<String> errors) {
        // API Key is required (unless using local model)
        if (isNullOrEmpty(llm.getApiKey()) && !isLocalProtocol(llm.getProtocol())) {
            errors.add("llm.apiKey: API key is required (use --api-key or set UT_AGENT_API_KEY env var)");
        }

        // Model name is required
        if (isNullOrEmpty(llm.getModelName())) {
            errors.add("llm.modelName: Model name is required (use --model)");
        }

        // Protocol is required
        if (isNullOrEmpty(llm.getProtocol())) {
            errors.add("llm.protocol: Protocol is required (openai | anthropic | gemini)");
        } else {
            String protocol = llm.getProtocol().toLowerCase();
            if (!protocol.equals("openai") && !protocol.equals("openai-zhipu") &&
                !protocol.equals("anthropic") && !protocol.equals("gemini")) {
                errors.add("llm.protocol: Invalid protocol '" + llm.getProtocol() + 
                          "'. Supported: openai, openai-zhipu, anthropic, gemini");
            }
        }
    }

    /**
     * Applies intelligent default values to the configuration.
     *
     * @param config the configuration to apply defaults to
     */
    public static void applyDefaults(AppConfig config) {
        if (config == null) {
            return;
        }

        // Apply LLM defaults
        if (config.getLlm() != null) {
            AppConfig.LlmConfig llm = config.getLlm();
            
            if (llm.getTemperature() == null) {
                llm.setTemperature(DEFAULT_TEMPERATURE);
                log.debug("Applied default temperature: {}", DEFAULT_TEMPERATURE);
            }
            
            if (llm.getTimeout() == null) {
                llm.setTimeout(DEFAULT_TIMEOUT);
                log.debug("Applied default timeout: {}s", DEFAULT_TIMEOUT);
            }
        }

        // Apply Workflow defaults
        if (config.getWorkflow() == null) {
            config.setWorkflow(new AppConfig.WorkflowConfig());
        }
        AppConfig.WorkflowConfig workflow = config.getWorkflow();
        
        if (workflow.getMaxRetries() <= 0) {
            workflow.setMaxRetries(DEFAULT_MAX_RETRIES);
            log.debug("Applied default maxRetries: {}", DEFAULT_MAX_RETRIES);
        }
        
        if (workflow.getCoverageThreshold() <= 0) {
            workflow.setCoverageThreshold(DEFAULT_COVERAGE_THRESHOLD);
            log.debug("Applied default coverageThreshold: {}%", DEFAULT_COVERAGE_THRESHOLD);
        }

        // Apply Batch defaults
        if (config.getBatch() == null) {
            config.setBatch(new AppConfig.BatchConfig());
        }

        log.info("Configuration defaults applied");
    }

    /**
     * Validates and applies defaults in one call.
     *
     * @param config the configuration to process
     * @throws AgentToolException if required fields are missing
     */
    public static void validateAndApplyDefaults(AppConfig config) {
        applyDefaults(config);
        validate(config);
    }

    /**
     * Creates a validation summary for display to user.
     *
     * @param config the configuration to summarize
     * @return formatted string showing effective configuration
     */
    public static String getConfigSummary(AppConfig config) {
        if (config == null || config.getLlm() == null) {
            return "Configuration not loaded";
        }

        AppConfig.LlmConfig llm = config.getLlm();
        AppConfig.WorkflowConfig workflow = config.getWorkflow();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Configuration Summary ===\n");
        sb.append("LLM:\n");
        sb.append("  Protocol: ").append(llm.getProtocol()).append("\n");
        sb.append("  Model: ").append(llm.getModelName()).append("\n");
        sb.append("  Temperature: ").append(llm.getTemperature()).append("\n");
        sb.append("  Timeout: ").append(llm.getTimeout()).append("s\n");
        if (llm.getBaseUrl() != null && !llm.getBaseUrl().isEmpty()) {
            sb.append("  Base URL: ").append(llm.getBaseUrl()).append("\n");
        }
        sb.append("Workflow:\n");
        if (workflow != null) {
            sb.append("  Max Retries: ").append(workflow.getMaxRetries()).append("\n");
            sb.append("  Coverage Threshold: ").append(workflow.getCoverageThreshold()).append("%\n");
            sb.append("  Interactive: ").append(workflow.isInteractive()).append("\n");
        }
        sb.append("=============================\n");

        return sb.toString();
    }

    private static boolean isNullOrEmpty(String str) {
        if (str == null || str.trim().isEmpty()) {
            return true;
        }
        // Check if it's an unresolved environment variable
        return str.contains("${env:") && str.contains("}");
    }

    private static boolean isLocalProtocol(String protocol) {
        // Reserved for future local model support
        return protocol != null && protocol.toLowerCase().contains("local");
    }
}
