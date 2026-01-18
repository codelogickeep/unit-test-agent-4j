package com.codelogickeep.agent.ut.framework.prompt;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.util.PromptTemplateLoader;

import java.util.Map;

/**
 * 传统模式 Prompt 构建器
 */
public class TraditionalPromptBuilder implements PromptBuilder {
    private final AppConfig config;
    private final String targetFile;
    private final String coverageInfo;
    private final Map<String, String> additionalContext;

    public TraditionalPromptBuilder(AppConfig config, String targetFile, String coverageInfo,
                                   Map<String, String> additionalContext) {
        this.config = config;
        this.targetFile = targetFile;
        this.coverageInfo = coverageInfo;
        this.additionalContext = additionalContext;
    }

    @Override
    public String buildSystemPrompt() {
        String templatePath = config.getPrompts() != null ?
            config.getPrompts().get("system") : "prompts/system-prompt.st";
        return PromptTemplateLoader.loadTemplate(templatePath);
    }

    @Override
    public String buildUserMessage() {
        StringBuilder message = new StringBuilder();
        message.append("Target file: ").append(targetFile).append("\n\n");

        if (coverageInfo != null) {
            message.append("Current coverage:\n").append(coverageInfo).append("\n\n");
        }

        if (additionalContext != null) {
            additionalContext.forEach((key, value) ->
                message.append(key).append(":\n").append(value).append("\n\n")
            );
        }

        message.append("Please generate comprehensive unit tests for this class.");
        return message.toString();
    }
}
