package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.config.AppConfig;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * LLM 客户端 - 负责创建和配置各种 LLM 模型
 * 
 * 支持的协议：
 * - openai: OpenAI API 及兼容的第三方服务
 * - openai-zhipu: 智谱 AI GLM Coding Plan (OpenAI 兼容)
 * - anthropic: Anthropic Claude 系列（也支持智谱 AI Anthropic 兼容端点）
 * - gemini: Google Gemini 系列
 */
public class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final AppConfig.LlmConfig config;

    public LlmClient(AppConfig.LlmConfig config) {
        this.config = config;
    }

    public StreamingChatModel createStreamingModel() {
        String protocol = config.getProtocol() != null ? config.getProtocol().toLowerCase() : "openai";
        String baseUrl = config.getBaseUrl();
        Duration timeout = config.getTimeout() != null ? Duration.ofSeconds(config.getTimeout())
                : Duration.ofSeconds(60);

        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing LLM API Key. Please provide it via --api-key or in the configuration file.");
        }
        if (config.getModelName() == null || config.getModelName().isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing LLM Model Name. Please provide it via --model or in the configuration file.");
        }

        // 检查是否启用请求日志（用于调试 API 错误）
        boolean logRequests = Boolean.parseBoolean(System.getProperty("llm.log.requests", "false"));
        boolean logResponses = Boolean.parseBoolean(System.getProperty("llm.log.responses", "false"));

        // OpenAI 及兼容服务（包括智谱 GLM Coding Plan）
        if ("openai".equals(protocol) || "openai-zhipu".equals(protocol)) {
            log.info("Using OpenAI compatible protocol, baseUrl: {}", baseUrl);

            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                    .apiKey(config.getApiKey())
                    .modelName(config.getModelName())
                    .temperature(config.getTemperature())
                    .timeout(timeout)
                    .logRequests(logRequests)
                    .logResponses(logResponses);

            if (baseUrl != null && !baseUrl.isEmpty()) {
                builder.baseUrl(baseUrl);
            }

            return builder.build();
        }

        // Anthropic Claude（也支持智谱 AI Anthropic 兼容端点）
        if ("anthropic".equals(protocol)) {
            log.info("Using Anthropic protocol, baseUrl: {}", baseUrl);

            AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder builder = AnthropicStreamingChatModel
                    .builder()
                    .apiKey(config.getApiKey())
                    .modelName(config.getModelName())
                    .temperature(config.getTemperature())
                    .timeout(timeout);

            if (baseUrl != null && !baseUrl.isEmpty()) {
                builder.baseUrl(baseUrl);
            }

            return builder.build();
        }

        // Google Gemini
        if ("gemini".equals(protocol)) {
            // Gemini native API usually uses /v1beta
            if (baseUrl != null && !baseUrl.isEmpty()) {
                if (!baseUrl.endsWith("/v1beta") && !baseUrl.endsWith("/v1beta/")) {
                    baseUrl = baseUrl.endsWith("/") ? baseUrl + "v1beta" : baseUrl + "/v1beta";
                }
            }

            GoogleAiGeminiStreamingChatModel.GoogleAiGeminiStreamingChatModelBuilder builder = GoogleAiGeminiStreamingChatModel
                    .builder()
                    .apiKey(config.getApiKey())
                    .modelName(config.getModelName())
                    .temperature(config.getTemperature() != null ? config.getTemperature() : 0.0)
                    .timeout(timeout);

            if (baseUrl != null && !baseUrl.isEmpty()) {
                builder.baseUrl(baseUrl);
            }

            return builder.build();
        }

        throw new IllegalArgumentException("Unsupported protocol: " + protocol +
                ". Supported protocols: openai, openai-zhipu, anthropic, gemini");
    }
}
