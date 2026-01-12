package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.exception.AgentToolException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.codelogickeep.agent.ut.exception.AgentToolException.ErrorCode.*;

public class DirectoryTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(DirectoryTool.class);

    @Tool("List files and directories in a given path. Returns names suffixed with '/' if they are directories.")
    public List<String> listFiles(@P("Path to the directory to list") String path) {
        log.info("Tool Input - listFiles: path={}", path);
        
        if (path == null || path.trim().isEmpty()) {
            throw AgentToolException.builder(INVALID_ARGUMENT, "Path is null or empty")
                    .context("Provided path: " + path)
                    .suggestion("Please provide a valid directory path")
                    .build();
        }
        
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            throw AgentToolException.builder(DIRECTORY_NOT_FOUND, "Directory not found: " + path)
                    .context("Attempted to list: " + p.toAbsolutePath())
                    .suggestion("Verify the directory path is correct and exists")
                    .build();
        }
        if (!Files.isDirectory(p)) {
            throw AgentToolException.builder(INVALID_ARGUMENT, "Path is not a directory: " + path)
                    .context("The path points to a file, not a directory")
                    .suggestion("Use a directory path, not a file path")
                    .build();
        }
        
        try (Stream<Path> stream = Files.list(p)) {
            List<String> result = stream.map(file -> {
                if (Files.isDirectory(file)) {
                    return file.getFileName().toString() + "/";
                } else {
                    return file.getFileName().toString();
                }
            }).collect(Collectors.toList());
            log.info("Tool Output - listFiles: count={}", result.size());
            return result;
        } catch (IOException e) {
            throw AgentToolException.builder(IO_ERROR, "Failed to list directory: " + e.getMessage())
                    .context("Directory: " + path)
                    .suggestion("Check directory permissions")
                    .cause(e)
                    .build();
        }
    }

    @Tool("Check if a directory exists.")
    public boolean directoryExists(@P("Path to the directory to check") String path) {
        log.info("Tool Input - directoryExists: path={}", path);
        Path p = Paths.get(path);
        boolean exists = Files.exists(p) && Files.isDirectory(p);
        log.info("Tool Output - directoryExists: {}", exists);
        return exists;
    }

    @Tool("Create a directory and all non-existent parent directories.")
    public void createDirectory(@P("Path to the directory to create") String path) {
        log.info("Tool Input - createDirectory: path={}", path);
        
        if (path == null || path.trim().isEmpty()) {
            throw AgentToolException.builder(INVALID_ARGUMENT, "Path is null or empty")
                    .context("Provided path: " + path)
                    .suggestion("Please provide a valid directory path to create")
                    .build();
        }
        
        try {
            Path p = Paths.get(path);
            Files.createDirectories(p);
            log.info("Tool Output - createDirectory: SUCCESS");
        } catch (IOException e) {
            throw AgentToolException.builder(IO_ERROR, "Failed to create directory: " + e.getMessage())
                    .context("Directory: " + path)
                    .suggestion("Check parent directory permissions and disk space")
                    .cause(e)
                    .build();
        }
    }
}
