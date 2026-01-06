package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.config.AppConfig;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;

public class LlmClient {

    private final AppConfig.LlmConfig config;

    public LlmClient(AppConfig.LlmConfig config) {
        this.config = config;
    }

    public StreamingChatModel createStreamingModel() {
        String baseUrl = config.getBaseUrl();

        // Provide defaults for known providers if baseUrl is not explicitly set
        if (baseUrl == null || baseUrl.isEmpty()) {
            if ("deepseek".equalsIgnoreCase(config.getProvider())) {
                baseUrl = "https://api.deepseek.com";
            }
        }

        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .sendThinking(true)
                .temperature(config.getTemperature());

        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }

        if (config.getTimeout() != null) {
            builder.timeout(Duration.ofSeconds(config.getTimeout()));
        } else {
            builder.timeout(Duration.ofSeconds(60));
        }

        return builder.build();
    }
}
