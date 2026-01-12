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
            throw new AgentToolException(INVALID_ARGUMENT, 
                    "Path is null or empty",
                    "Provided path: " + path,
                    "Please provide a valid directory path");
        }
        
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            throw new AgentToolException(DIRECTORY_NOT_FOUND, 
                    "Directory not found: " + path,
                    "Attempted to list: " + p.toAbsolutePath(),
                    "Verify the directory path is correct and exists");
        }
        if (!Files.isDirectory(p)) {
            throw new AgentToolException(INVALID_ARGUMENT, 
                    "Path is not a directory: " + path,
                    "The path points to a file, not a directory",
                    "Use a directory path, not a file path");
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
            throw new AgentToolException(IO_ERROR, 
                    "Failed to list directory: " + e.getMessage(),
                    "Directory: " + path,
                    "Check directory permissions", e);
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
            throw new AgentToolException(INVALID_ARGUMENT, 
                    "Path is null or empty",
                    "Provided path: " + path,
                    "Please provide a valid directory path to create");
        }
        
        try {
            Path p = Paths.get(path);
            Files.createDirectories(p);
            log.info("Tool Output - createDirectory: SUCCESS");
        } catch (IOException e) {
            throw new AgentToolException(IO_ERROR, 
                    "Failed to create directory: " + e.getMessage(),
                    "Directory: " + path,
                    "Check parent directory permissions and disk space", e);
        }
    }
}
