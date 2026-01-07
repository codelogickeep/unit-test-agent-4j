package com.codelogickeep.agent.ut.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class MavenExecutorTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(MavenExecutorTool.class);

    public record ExecutionResult(int exitCode, String stdOut, String stdErr) {
        //java.lang.ProcessBuilder
    }


    @Tool("Compile the project (src and test) using Maven. Useful to check for syntax errors before running tests.")
    public ExecutionResult compileProject() throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            command.add("cmd.exe");
            command.add("/c");
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
        String cmdString = String.join(" ", command);
        System.out.println("\n>>> Executing Command: " + cmdString);
        
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

        System.out.println(">>> Command finished with exit code: " + process.exitValue() + "\n");
        return new ExecutionResult(process.exitValue(), outBuilder.toString(), errBuilder.toString());
    }

    @Tool("Execute Maven tests for a specific class. Returns exit code and output.")
    public ExecutionResult executeTest(@P("The full name of the test class to execute (e.g., com.example.MyTest)") String testClassName) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            command.add("cmd.exe");
            command.add("/c");
            command.add("mvn");
        } else {
            command.add("mvn");
        }

        command.add("test");
        command.add("-Dtest=" + testClassName);
        command.add("-B");

        return executeCommand(command);
    }

    private String readStream(java.io.InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}