package com.codelogickeep.agent.ut.framework.prompt;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.util.PromptTemplateLoader;
import com.codelogickeep.agent.ut.model.MethodCoverageInfo;

import java.util.List;
import java.util.Map;

/**
 * 迭代模式 Prompt 构建器
 */
public class IterativePromptBuilder implements PromptBuilder {
    private final AppConfig config;
    private final String targetFile;
    private final List<MethodCoverageInfo> methodCoverages;
    private final Map<String, String> additionalContext;

    public IterativePromptBuilder(AppConfig config, String targetFile,
                                 List<MethodCoverageInfo> methodCoverages,
                                 Map<String, String> additionalContext) {
        this.config = config;
        this.targetFile = targetFile;
        this.methodCoverages = methodCoverages;
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
        message.append("Iterative mode: Generate tests method by method based on priority.\n\n");

        if (methodCoverages != null && !methodCoverages.isEmpty()) {
            message.append("Methods to test (by priority):\n");
            for (MethodCoverageInfo method : methodCoverages) {
                message.append(String.format("- [%s] %s (Line: %.1f%%, Branch: %.1f%%)\n",
                    method.getPriority(), method.getMethodName(),
                    method.getLineCoverage(), method.getBranchCoverage()));
            }
            message.append("\n");
        }

        if (additionalContext != null) {
            additionalContext.forEach((key, value) ->
                message.append(key).append(":\n").append(value).append("\n\n")
            );
        }

        message.append("Please generate tests for the highest priority methods first.");
        return message.toString();
    }
}
