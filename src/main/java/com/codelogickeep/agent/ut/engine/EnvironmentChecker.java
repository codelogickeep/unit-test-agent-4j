package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.config.AppConfig;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class EnvironmentChecker {

    public static void check(AppConfig config) {
        System.out.println("\n>>> Starting Environment Check...\n");

        if (config.getLlm() != null) {
            System.out.println("LLM Protocol: " + config.getLlm().getProtocol());
            System.out.println("LLM Model:    " + config.getLlm().getModelName());
            System.out.println("Temperature:  " + config.getLlm().getTemperature());
        }
        if (config.getWorkflow() != null) {
            System.out.println("Max Retries:  " + config.getWorkflow().getMaxRetries());
        }
        System.out.println();

        boolean mvnOk = checkMaven();
        boolean llmOk = checkLlm(config);
        boolean permOk = checkPermissions();

        System.out.println("\n>>> Environment Check Summary:");
        System.out.println("Maven: " + (mvnOk ? "OK" : "FAILED"));
        System.out.println("LLM: " + (llmOk ? "OK" : "FAILED"));
        System.out.println("Permissions: " + (permOk ? "OK" : "FAILED"));

        if (!mvnOk || !llmOk || !permOk) {
            System.out.println("\n>>> Please fix the issues above before running the agent.");
        } else {
            System.out.println("\n>>> Environment is ready!");
        }
    }

    private static boolean checkMaven() {
        System.out.print("Checking Maven... ");
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = new ProcessBuilder(isWindows ? "mvn.cmd" : "mvn", "-version");
            if (isWindows) {
                pb = new ProcessBuilder("cmd.exe", "/c", "mvn", "-version");
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                System.out.println("OK (Maven found)");
                return true;
            } else {
                System.out.println("FAILED (Maven not found or timed out)");
                System.out.println("  Suggestion: Ensure 'mvn' is installed and in your PATH.");
                return false;
            }
        } catch (Exception e) {
            System.out.println("FAILED (" + e.getMessage() + ")");
            System.out.println("  Suggestion: Ensure 'mvn' is installed and in your PATH.");
            return false;
        }
    }

    private static boolean checkLlm(AppConfig config) {
        System.out.print("Checking LLM Configuration... ");
        if (config == null || config.getLlm() == null) {
            System.out.println("FAILED (No LLM config found)");
            return false;
        }

        try {
            if (config.getLlm().getApiKey() == null || config.getLlm().getApiKey().isEmpty()) {
                System.out.println("FAILED (Missing API Key)");
                return false;
            }
            
            LlmClient llmClient = new LlmClient(config.getLlm());
            StreamingChatModel model = llmClient.createStreamingModel();
            
            CountDownLatch latch = new CountDownLatch(1);
            final boolean[] success = {false};
            final String[] error = {null};

            model.chat("ping", new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {}

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    success[0] = true;
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    error[0] = (t.getMessage() != null && !t.getMessage().isEmpty()) ? t.getMessage() : t.toString();
                    latch.countDown();
                }
            });

            if (latch.await(30, TimeUnit.SECONDS)) {
                if (success[0]) {
                    System.out.println("OK (Configuration is valid and LLM is reachable)");
                    return true;
                } else {
                    System.out.println("FAILED (" + error[0] + ")");
                    return false;
                }
            } else {
                System.out.println("FAILED (Timed out waiting for LLM response)");
                return false;
            }
        } catch (Exception e) {
            System.out.println("FAILED (" + e.getMessage() + ")");
            return false;
        }
    }

    private static boolean checkPermissions() {
        System.out.println("Checking Permissions... ");
        boolean allOk = true;

        // 1. Check OS Write Permissions in current directory
        File testFile = new File("test_perm_check.tmp");
        try {
            if (testFile.createNewFile()) {
                testFile.delete();
                System.out.println("  [OS] Write to current directory: OK");
            } else {
                System.out.println("  [OS] Write to current directory: FAILED (Cannot create file)");
                allOk = false;
            }
        } catch (IOException e) {
            System.out.println("  [OS] Write to current directory: FAILED (" + e.getMessage() + ")");
            allOk = false;
        }

        // 2. Check Directory Creation in src/test/java
        Path testDir = Paths.get("src", "test", "java", "com", "codelogickeep", "agent", "ut", "envcheck");
        try {
            Files.createDirectories(testDir);
            File testFileInDir = testDir.resolve("EnvCheckTest.java").toFile();
            if (testFileInDir.createNewFile()) {
                System.out.println("  [OS] Create directory and file in src/test/java: OK");
                testFileInDir.delete();
                Files.delete(testDir); // Only deletes if empty, which matches our case
            } else {
                System.out.println("  [OS] Create directory and file in src/test/java: FAILED (Cannot create file)");
                allOk = false;
            }
        } catch (IOException e) {
            System.out.println("  [OS] Create directory and file in src/test/java: FAILED (" + e.getMessage() + ")");
            System.out.println(
                    "    Suggestion: Ensure you have write permissions to 'src/test/java' and it exists (or can be created).");
            allOk = false;
        }

        return allOk;
    }
}
