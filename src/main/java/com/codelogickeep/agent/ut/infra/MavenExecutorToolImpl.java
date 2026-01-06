package com.codelogickeep.agent.ut.infra;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import dev.langchain4j.agent.tool.Tool;

public class MavenExecutorToolImpl implements AgentTool {

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
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        String stdOut = readStream(process.getInputStream());
        String stdErr = readStream(process.getErrorStream());

        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Maven execution timed out");
        }

        return new ExecutionResult(process.exitValue(), stdOut, stdErr);
    }

    @Tool("Execute Maven tests for a specific class. Returns exit code and output.")
    public ExecutionResult executeTest(String testClassName) throws IOException, InterruptedException {
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