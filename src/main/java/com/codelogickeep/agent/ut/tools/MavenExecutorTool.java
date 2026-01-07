package com.codelogickeep.agent.ut.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class MavenExecutorTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(MavenExecutorTool.class);
    private static String cachedShell = null;

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


    @Tool("Compile the project (src and test) using Maven. Useful to check for syntax errors before running tests.")
    public ExecutionResult compileProject() throws IOException, InterruptedException {
        log.info("Tool Input - compileProject");
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

        return executeCommand(command);
    }

    private ExecutionResult executeCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
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
        command.add("-Dtest=" + testClassName);
        command.add("-B");

        return executeCommand(command);
    }
}