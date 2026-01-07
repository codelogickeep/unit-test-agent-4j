package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.config.AppConfig;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;

public class LlmClient {

    private final AppConfig.LlmConfig config;

    public LlmClient(AppConfig.LlmConfig config) {
        this.config = config;
    }

    public StreamingChatModel createStreamingModel() {
        String protocol = config.getProtocol() != null ? config.getProtocol().toLowerCase() : "openai";
        String baseUrl = config.getBaseUrl();
        Duration timeout = config.getTimeout() != null ? Duration.ofSeconds(config.getTimeout()) : Duration.ofSeconds(60);

        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            throw new IllegalArgumentException("Missing LLM API Key. Please provide it via --api-key or in the configuration file.");
        }
        if (config.getModelName() == null || config.getModelName().isEmpty()) {
            throw new IllegalArgumentException("Missing LLM Model Name. Please provide it via --model or in the configuration file.");
        }

        // Normalize baseUrl based on protocol
        if (baseUrl != null && !baseUrl.isEmpty()) {
            if ("gemini".equals(protocol)) {
                // Gemini native API usually uses /v1beta
                if (!baseUrl.endsWith("/v1beta") && !baseUrl.endsWith("/v1beta/")) {
                    baseUrl = baseUrl.endsWith("/") ? baseUrl + "v1beta" : baseUrl + "/v1beta";
                }
            } else {
                // OpenAI and others usually use /v1
                if (!baseUrl.endsWith("/v1") && !baseUrl.endsWith("/v1/")) {
                    baseUrl = baseUrl.endsWith("/") ? baseUrl + "v1" : baseUrl + "/v1";
                }
            }
        }

        if ("openai".equals(protocol)) {
            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                    .apiKey(config.getApiKey())
                    .modelName(config.getModelName())
                    .temperature(config.getTemperature())
                    .timeout(timeout);

            if (baseUrl != null && !baseUrl.isEmpty()) {
                builder.baseUrl(baseUrl);
            }

            return builder.build();
        } else if ("anthropic".equals(protocol)) {
            AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder builder = AnthropicStreamingChatModel.builder()
                    .apiKey(config.getApiKey())
                    .modelName(config.getModelName())
                    .temperature(config.getTemperature())
                    .timeout(timeout);

            if (baseUrl != null && !baseUrl.isEmpty()) {
                builder.baseUrl(baseUrl);
            }

            return builder.build();
        } else if ("gemini".equals(protocol)) {
            GoogleAiGeminiStreamingChatModel.GoogleAiGeminiStreamingChatModelBuilder builder = GoogleAiGeminiStreamingChatModel.builder()
                    .apiKey(config.getApiKey())
                    .modelName(config.getModelName())
                    .temperature(config.getTemperature() != null ? config.getTemperature() : 0.0)
                    .timeout(timeout);

            if (baseUrl != null && !baseUrl.isEmpty()) {
                builder.baseUrl(baseUrl);
            }

            return builder.build();
        }

        throw new IllegalArgumentException("Unsupported protocol: " + protocol);
    }
}
