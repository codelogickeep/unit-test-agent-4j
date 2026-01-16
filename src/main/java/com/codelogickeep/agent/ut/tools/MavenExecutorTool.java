package com.codelogickeep.agent.ut.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codelogickeep.agent.ut.framework.annotation.P;
import com.codelogickeep.agent.ut.framework.annotation.Tool;

public class MavenExecutorTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(MavenExecutorTool.class);
    private static String cachedShell = null;
    private Path projectRoot = Paths.get(".").toAbsolutePath().normalize();

    /**
     * Set the project root directory where Maven commands will be executed.
     */
    public void setProjectRoot(String rootPath) {
        if (rootPath != null) {
            this.projectRoot = Paths.get(rootPath).toAbsolutePath().normalize();
            log.info("MavenExecutorTool project root set to: {}", projectRoot);
        }
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public record ExecutionResult(int exitCode, String stdOut, String stdErr) {
        //java.lang.ProcessBuilder
    }

    private String getShell() {
        if (cachedShell != null) {
            return cachedShell;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (!isWindows) {
            cachedShell = "sh";
            return cachedShell;
        }

        // Try pwsh first (PowerShell 7)
        String[] psCommands = {"pwsh", "pwsh.exe"};
        for (String cmd : psCommands) {
            try {
                Process p = new ProcessBuilder(cmd, "-Command", "$PSVersionTable.PSVersion.Major").start();
                if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    cachedShell = cmd;
                    log.info("PowerShell 7 ({}) detected and will be used.", cmd);
                    return cachedShell;
                }
            } catch (Exception e) {
                // Ignore and try next
            }
        }

        // Fallback to powershell.exe (Windows PowerShell) if ps7 not found
        try {
            Process p = new ProcessBuilder("powershell.exe", "-Command", "$PSVersionTable.PSVersion.Major").start();
            if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
                cachedShell = "powershell.exe";
                log.info("Windows PowerShell (powershell.exe) detected and will be used.");
                return cachedShell;
            }
        } catch (Exception e) {
            // Ignore
        }

        cachedShell = "cmd.exe";
        log.info("PowerShell not found or failed to start, falling back to cmd.exe.");
        return cachedShell;
    }


    @Tool("Compile the project (src and test) using Maven. IMPORTANT: Must run checkSyntax() first and ensure it passes before calling this.")
    public ExecutionResult compileProject() throws IOException, InterruptedException {
        log.info("Tool Input - compileProject");
        
        // 检查编译守卫
        CompileGuard.CompileCheckResult checkResult = CompileGuard.getInstance().canCompile();
        if (!checkResult.canCompile()) {
            log.warn("Compile blocked by CompileGuard: {}", checkResult.blockReason());
            // 返回一个特殊的错误结果，而不是抛出异常
            return new ExecutionResult(-1, "", checkResult.blockReason());
        }
        
        List<String> command = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            String shell = getShell();
            command.add(shell);
            command.add(shell.contains("powershell") || shell.contains("pwsh") ? "-Command" : "/c");
            command.add("mvn");
        } else {
            command.add("mvn");
        }

        // Compile both src and test
        command.add("test-compile");
        command.add("-B");

        ExecutionResult result = executeCommand(command);
        log.info("Tool Output - compileProject: exitCode={}", result.exitCode());
        return result;
    }

    private ExecutionResult executeCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectRoot.toFile());
        log.debug("Executing Maven in directory: {}", projectRoot);
        Process process = pb.start();

        // Use threads to read and print output in real-time
        StringBuilder outBuilder = new StringBuilder();
        StringBuilder errBuilder = new StringBuilder();

        Thread outThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    outBuilder.append(line).append("\n");
                }
            } catch (IOException e) {
                log.error("Error reading stdout", e);
            }
        });

        Thread errThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.err.println(line);
                    errBuilder.append(line).append("\n");
                }
            } catch (IOException e) {
                log.error("Error reading stderr", e);
            }
        });

        outThread.start();
        errThread.start();

        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        outThread.join();
        errThread.join();

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Maven execution timed out");
        }

        log.info("Tool Output - Command finished with exit code: {}", process.exitValue());
        return new ExecutionResult(process.exitValue(), outBuilder.toString(), errBuilder.toString());
    }

    @Tool("Execute Maven tests for a specific class. Returns exit code and output.")
    public ExecutionResult executeTest(@P("The full name of the test class to execute (e.g., com.example.MyTest)") String testClassName) throws IOException, InterruptedException {
        log.info("Tool Input - executeTest: testClassName={}", testClassName);
        
        // 检查编译守卫 - Maven test 会先执行 test-compile
        CompileGuard.CompileCheckResult checkResult = CompileGuard.getInstance().canCompile();
        if (!checkResult.canCompile()) {
            log.warn("Test blocked by CompileGuard: {}", checkResult.blockReason());
            return new ExecutionResult(-1, "", checkResult.blockReason());
        }
        
        List<String> command = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            String shell = getShell();
            command.add(shell);
            command.add(shell.contains("powershell") || shell.contains("pwsh") ? "-Command" : "/c");
            command.add("mvn");
        } else {
            command.add("mvn");
        }

        command.add("test");
        // Quote the test class name to prevent PowerShell from misinterpreting dots
        String shell = isWindows ? getShell() : null;
        if (shell != null && (shell.contains("powershell") || shell.contains("pwsh"))) {
            command.add("\"-Dtest=" + testClassName + "\"");
        } else {
            command.add("-Dtest=" + testClassName);
        }
        command.add("-B");

        ExecutionResult result = executeCommand(command);
        log.info("Tool Output - executeTest: exitCode={}", result.exitCode());
        return result;
    }
    
    @Tool("Clean and run all tests to generate fresh coverage data. Use this before analyzing coverage.")
    public ExecutionResult cleanAndTest() throws IOException, InterruptedException {
        log.info("Tool Input - cleanAndTest");
        
        // 检查编译守卫 - Maven test 会先执行 test-compile
        CompileGuard.CompileCheckResult checkResult = CompileGuard.getInstance().canCompile();
        if (!checkResult.canCompile()) {
            log.warn("Clean and test blocked by CompileGuard: {}", checkResult.blockReason());
            return new ExecutionResult(-1, "", checkResult.blockReason());
        }
        
        List<String> command = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            String shell = getShell();
            command.add(shell);
            command.add(shell.contains("powershell") || shell.contains("pwsh") ? "-Command" : "/c");
            command.add("mvn");
        } else {
            command.add("mvn");
        }

        // Clean and test to generate fresh coverage data
        command.add("clean");
        command.add("test");
        command.add("-B");

        ExecutionResult result = executeCommand(command);
        log.info("Tool Output - cleanAndTest: exitCode={}", result.exitCode());
        return result;
    }
}