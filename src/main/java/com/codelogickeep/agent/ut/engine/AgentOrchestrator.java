package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.config.AppConfig;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class AgentOrchestrator {

    private final AppConfig config;
    private final StreamingChatModel streamingLlm;
    private final List<Object> tools;

    public AgentOrchestrator(AppConfig config,
            StreamingChatModel streamingLlm,
            List<Object> tools) {
        this.config = config;
        this.streamingLlm = streamingLlm;
        this.tools = tools;
    }

    public void run(String targetFile) {
        run(targetFile, null);
    }

    public void run(String targetFile, String taskContext) {
        log.info("Starting Agent orchestration for target: {}", targetFile);

        // Load System Prompt dynamically
        String systemPrompt = loadSystemPrompt();
        
        // Debug: Print registered tool names
        for (Object tool : tools) {
             log.info("Agent Tool Registered: {}", tool.getClass().getSimpleName());
        }

        // Create the AI Service (Assistant)
        UnitTestAssistant assistant = AiServices.builder(UnitTestAssistant.class)
                .streamingChatModel(streamingLlm)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .tools(tools)
                .systemMessageProvider(chatMemoryId -> systemPrompt)
                .build();

        int maxRetries = config.getWorkflow() != null ? config.getWorkflow().getMaxRetries() : 0;
        int attempt = 0;
        boolean success = false;

        while (attempt <= maxRetries && !success) {
            if (attempt > 0) {
                log.info("Retrying... (Attempt {}/{})", attempt, maxRetries);
            }

            try {
                String userMessage = taskContext != null
                        ? taskContext + "\n\nTarget file: " + targetFile
                        : targetFile;
                TokenStream tokenStream = assistant.generateTest(userMessage);
                StringBuilder contentBuilder = new StringBuilder();
                CompletableFuture<String> future = new CompletableFuture<>();

                tokenStream.onPartialResponse(token -> {
                    System.out.print(token);
                    System.out.flush();
                    contentBuilder.append(token);
                })
                        .onCompleteResponse(response -> {
                            System.out.println();
                            future.complete(contentBuilder.toString());
                        })
                        .onError(future::completeExceptionally)
                        .start();

                String result = future.join();
                log.info("Agent completed task. Result length: {}", result.length());
                success = true;
            } catch (Exception e) {
                attempt++;
                log.error("Agent failed on attempt {}", attempt, e);
                
                if (attempt <= maxRetries) {
                    long waitTime = (long) Math.pow(2, attempt) * 1000;
                    log.info("Waiting {}ms before retrying...", waitTime);
                    try {
                        Thread.sleep(waitTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    log.error("Max retries reached. Aborting.");
                }
            }
        }
    }

    private String loadSystemPrompt() {
        String defaultPrompt = """
                You are an expert Java QA Engineer. Your task is to analyze Java code and
                generate JUnit 5 tests.
                Always use the provided tools to read files, write tests, and run them.
                """;

        if (config.getPrompts() != null && config.getPrompts().containsKey("system")) {
            String pathStr = config.getPrompts().get("system");
            log.info("Loading system prompt from: {}", pathStr);
            
            try {
                // 1. Try file system relative to working directory
                Path path = Paths.get(pathStr);
                if (Files.exists(path)) {
                    return Files.readString(path, StandardCharsets.UTF_8);
                }
                
                // 2. Try classpath
                // Remove leading slash if present for classpath resource loading
                String resourcePath = pathStr.startsWith("/") ? pathStr.substring(1) : pathStr;
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (in != null) {
                        log.info("Loaded system prompt from classpath: {}", resourcePath);
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
                
                log.warn("System prompt file not found at: {}. Using default fallback prompt.", pathStr);
            } catch (IOException e) {
                log.warn("Failed to load system prompt from {}. Using default fallback prompt.", pathStr, e);
            }
        }
        
        log.info("Using default hardcoded system prompt.");
        return defaultPrompt;
    }

    interface UnitTestAssistant {
        // SystemMessage is now provided dynamically
        TokenStream generateTest(String targetFilePath);
    }
}
