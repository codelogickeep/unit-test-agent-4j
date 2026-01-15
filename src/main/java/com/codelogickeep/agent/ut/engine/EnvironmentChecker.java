package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.adapter.LlmAdapter;
import com.codelogickeep.agent.ut.framework.adapter.LlmAdapterFactory;
import com.codelogickeep.agent.ut.framework.model.AssistantMessage;
import com.codelogickeep.agent.ut.framework.model.Message;
import com.codelogickeep.agent.ut.framework.model.UserMessage;
import com.codelogickeep.agent.ut.tools.JdtLsManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 环境检查器
 */
public class EnvironmentChecker {
    private static final Logger log = LoggerFactory.getLogger(EnvironmentChecker.class);

    /**
     * 检查环境（仅检查模式）
     */
    public static void check(AppConfig config) {
        check(config, null);
    }

    /**
     * 检查环境并返回是否可以继续执行
     * 
     * @return true 如果环境检查通过（至少 LLM 配置正确），false 否则
     */
    public static boolean check(AppConfig config, String projectRoot) {
        System.out.println("\n>>> Starting Environment Check...\n");

        if (config.getLlm() != null) {
            System.out.println("LLM Protocol: " + config.getLlm().getProtocol());
            System.out.println("LLM Model:    " + config.getLlm().getModelName());
            System.out.println("Temperature:  " + config.getLlm().getTemperature());
        }
        if (config.getWorkflow() != null) {
            System.out.println("Max Retries:  " + config.getWorkflow().getMaxRetries());
        }
        if (projectRoot != null) {
            System.out.println("Project Root: " + projectRoot);
        }
        System.out.println();

        boolean mvnOk = checkMaven();
        boolean llmOk = checkLlm(config);
        boolean permOk = checkPermissions();
        boolean lspOk = checkLsp(config);
        boolean projectOk = projectRoot == null || auditProject(config, projectRoot);

        System.out.println("\n>>> Environment Check Summary:");
        System.out.println("Maven:       " + (mvnOk ? "OK" : "FAILED"));
        System.out.println("LLM:         " + (llmOk ? "OK" : "FAILED"));
        System.out.println("Permissions: " + (permOk ? "OK" : "FAILED"));
        System.out.println("LSP Server:  " + (lspOk ? "OK" : "OPTIONAL (Auto-download available)"));
        if (projectRoot != null) {
            System.out.println("Project Dep: " + (projectOk ? "OK" : "WARNING (Dependency issues found)"));
        }

        // LLM 检查失败是致命错误，必须停止
        if (!llmOk) {
            System.out.println("\n>>> CRITICAL: LLM configuration test FAILED!");
            System.out.println("    Please check your configuration file and ensure:");
            System.out.println("    1. API Key is correct and valid");
            System.out.println("    2. Protocol matches your LLM provider:");
            System.out.println("       - 'openai' for OpenAI GPT models");
            System.out.println(
                    "       - 'openai-zhipu' for Zhipu GLM Coding Plan (baseUrl: https://open.bigmodel.cn/api/coding/paas/v4)");
            System.out.println("       - 'anthropic' for Claude models");
            System.out.println("       - 'gemini' for Google Gemini");
            System.out.println("    3. Model name is correct for your provider");
            System.out.println("    4. BaseUrl is correct (if using custom endpoint)");
            System.out.println("\n>>> Agent will NOT start until LLM configuration is fixed.");
            return false;
        }

        if (!mvnOk || !permOk) {
            System.out.println("\n>>> Please fix the CRITICAL issues above before running the agent.");
            return false;
        }

        if (projectRoot != null && !projectOk) {
            System.out.println("\n>>> WARNING: Project has missing or outdated recommended dependencies.");
            System.out.println("    The Agent will attempt to fix pom.xml automatically during execution.");
        } else {
            System.out.println("\n>>> Environment is ready!");
        }

        return true;
    }

    /**
     * 检查 LSP 服务 (JDT Language Server) 可用性
     * 自动下载如果未安装
     */
    private static boolean checkLsp(AppConfig config) {
        // 检查配置中是否启用了 LSP
        boolean lspEnabled = config.getWorkflow() != null &&
                config.getWorkflow().isUseLsp();

        if (!lspEnabled) {
            System.out.print("Checking LSP Server (JDT LS)... ");
            System.out.println("SKIPPED (LSP not enabled in config, use 'use-lsp: true' to enable)");
            return true;
        }

        System.out.print("Checking LSP Server (JDT LS)... ");
        try {
            JdtLsManager manager = new JdtLsManager();
            if (manager.ensureJdtLsAvailable()) {
                System.out.println("OK (JDT Language Server available)");
                return true;
            } else {
                System.out.println("WARNING (JDT LS not found, will auto-download on first use)");
                return false;
            }
        } catch (Exception e) {
            System.out.println("WARNING (" + e.getMessage() + ")");
            System.out.println("  Note: LSP is optional. JavaParser will be used as fallback.");
            return false;
        }
    }

    private static boolean auditProject(AppConfig config, String projectRoot) {
        System.out.print("Auditing Project Dependencies... ");
        Path pomPath = Paths.get(projectRoot, "pom.xml");
        if (!Files.exists(pomPath)) {
            System.out.println("SKIPPED (pom.xml not found)");
            return true;
        }

        Map<String, String> required = config.getDependencies();
        if (required == null || required.isEmpty()) {
            System.out.println("OK (No version requirements defined)");
            return true;
        }

        try {
            String content = Files.readString(pomPath);
            List<String> missing = new ArrayList<>();
            List<String> outdated = new ArrayList<>();

            for (Map.Entry<String, String> entry : required.entrySet()) {
                String artifactId = entry.getKey();
                String minVersion = entry.getValue();

                if (!content.contains(artifactId)) {
                    missing.add(artifactId);
                } else {
                    // Try to find the version using regex
                    String currentVersion = extractVersion(content, artifactId);
                    if (currentVersion != null && isVersionLower(currentVersion, minVersion)) {
                        outdated.add(artifactId + " (" + currentVersion + " < " + minVersion + ")");
                    }
                }
            }

            if (missing.isEmpty() && outdated.isEmpty()) {
                System.out.println("OK");
                return true;
            } else {
                System.out.println("WARNING");
                if (!missing.isEmpty())
                    System.out.println("  Missing components: " + String.join(", ", missing));
                if (!outdated.isEmpty())
                    System.out.println("  Outdated components: " + String.join(", ", outdated));
                return false;
            }
        } catch (IOException e) {
            System.out.println("FAILED (Error reading pom.xml)");
            return false;
        }
    }

    private static String extractVersion(String pomContent, String artifactId) {
        // Look for <artifactId>... followed by <version>...
        String regex = "<artifactId>" + Pattern.quote(artifactId)
                + "</artifactId>\\s*(?:<[^>]*>\\s*)*<version>([^<]+)</version>";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(pomContent);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // Also look for properties like <jacoco.version> if version is a placeholder
        // This is a simplified check
        return null;
    }

    private static boolean isVersionLower(String current, String required) {
        if (current.startsWith("${") || required.startsWith("${"))
            return false; // Skip property placeholders

        String[] v1 = current.split("[\\.\\-]");
        String[] v2 = required.split("[\\.\\-]");

        int length = Math.max(v1.length, v2.length);
        for (int i = 0; i < length; i++) {
            int n1 = i < v1.length ? tryParseInt(v1[i]) : 0;
            int n2 = i < v2.length ? tryParseInt(v2[i]) : 0;
            if (n1 < n2)
                return true;
            if (n1 > n2)
                return false;
        }
        return false;
    }

    private static int tryParseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
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

            // 使用自研框架进行检查
            LlmAdapter adapter = LlmAdapterFactory.create(config.getLlm());

            log.info("Using {} for LLM check", adapter.getName());

            // 简单的 ping 测试
            List<Message> messages = new ArrayList<>();
            messages.add(new UserMessage("ping"));

            AssistantMessage response = adapter.chat(messages, null);

            if (response != null && (response.content() != null || response.hasToolCalls())) {
                System.out.println("OK (" + adapter.getName() + ")");
                return true;
            } else {
                System.out.println("FAILED (Empty response from LLM)");
                return false;
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            // 截断长消息
            if (msg.length() > 100) {
                msg = msg.substring(0, 100) + "...";
            }
            System.out.println("FAILED (" + msg + ")");
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
