package com.codelogickeep.agent.ut.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DirectoryTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(DirectoryTool.class);

    @Tool("List files and directories in a given path. Returns names suffixed with '/' if they are directories.")
    public List<String> listFiles(@P("Path to the directory to list") String path) throws IOException {
        log.info("Listing files in directory: {}", path);
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            throw new IOException("Directory not found: " + path);
        }
        if (!Files.isDirectory(p)) {
            throw new IOException("Path is not a directory: " + path);
        }
        
        try (Stream<Path> stream = Files.list(p)) {
            return stream.map(file -> {
                if (Files.isDirectory(file)) {
                    return file.getFileName().toString() + "/";
                } else {
                    return file.getFileName().toString();
                }
            }).collect(Collectors.toList());
        }
    }

    @Tool("Check if a directory exists.")
    public boolean directoryExists(@P("Path to the directory to check") String path) {
        log.info("Checking if directory exists: {}", path);
        Path p = Paths.get(path);
        return Files.exists(p) && Files.isDirectory(p);
    }

    @Tool("Create a directory and all non-existent parent directories.")
    public void createDirectory(@P("Path to the directory to create") String path) throws IOException {
        log.info("Creating directory: {}", path);
        Path p = Paths.get(path);
        Files.createDirectories(p);
    }
}
