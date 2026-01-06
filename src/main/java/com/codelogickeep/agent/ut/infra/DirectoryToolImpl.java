package com.codelogickeep.agent.ut.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.langchain4j.agent.tool.Tool;

public class DirectoryToolImpl implements AgentTool {

    @Tool("List files and directories in a given path. Returns names suffixed with '/' if they are directories.")
    public List<String> listFiles(String path) throws IOException {
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
    public boolean directoryExists(String path) {
        Path p = Paths.get(path);
        return Files.exists(p) && Files.isDirectory(p);
    }

    @Tool("Create a directory and all non-existent parent directories.")
    public void createDirectory(String path) throws IOException {
        Path p = Paths.get(path);
        Files.createDirectories(p);
    }
}
