package com.codelogickeep.agent.ut.framework.adapter;

import com.codelogickeep.agent.ut.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * LLM 适配器工厂 - 根据配置创建对应的适配器
 */
public class LlmAdapterFactory {
    private static final Logger log = LoggerFactory.getLogger(LlmAdapterFactory.class);
    
    /**
     * 根据配置创建适配器
     */
    public static LlmAdapter create(AppConfig.LlmConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("LLM config is required");
        }
        
        String protocol = config.getProtocol() != null ? config.getProtocol().toLowerCase() : "openai";
        String baseUrl = config.getBaseUrl();
        String apiKey = config.getApiKey();
        String model = config.getModelName();
        Double temperature = config.getTemperature();
        Duration timeout = config.getTimeout() != null 
                ? Duration.ofSeconds(config.getTimeout()) 
                : Duration.ofSeconds(120);
        
        // 检查是否启用请求日志
        boolean logRequests = Boolean.parseBoolean(System.getProperty("llm.log.requests", "false"));
        
        log.info("Creating LLM adapter: protocol={}, model={}, baseUrl={}", protocol, model, baseUrl);
        
        return switch (protocol) {
            // OpenAI 及兼容服务（包括智谱 AI）
            case "openai", "openai-zhipu", "zhipu" -> OpenAiAdapter.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .model(model)
                    .temperature(temperature)
                    .timeout(timeout)
                    .logRequests(logRequests)
                    .build();
                    
            case "anthropic", "claude" -> ClaudeAdapter.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .model(model)
                    .temperature(temperature)
                    .timeout(timeout)
                    .logRequests(logRequests)
                    .build();
                    
            case "gemini", "google" -> GeminiAdapter.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .model(model)
                    .temperature(temperature)
                    .timeout(timeout)
                    .logRequests(logRequests)
                    .build();
                    
            default -> throw new IllegalArgumentException(
                    "Unsupported protocol: " + protocol + 
                    ". Supported: openai, openai-zhipu, anthropic, gemini");
        };
    }
    
    /**
     * 测试适配器连接
     */
    public static boolean testConnection(LlmAdapter adapter) {
        try {
            log.info("Testing connection to {}...", adapter.getName());
            boolean success = adapter.testConnection();
            if (success) {
                log.info("Connection test passed for {}", adapter.getName());
            } else {
                log.warn("Connection test failed for {}", adapter.getName());
            }
            return success;
        } catch (Exception e) {
            log.error("Connection test error for {}: {}", adapter.getName(), e.getMessage());
            return false;
        }
    }
}
