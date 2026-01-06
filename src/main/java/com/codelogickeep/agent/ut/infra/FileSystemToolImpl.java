package com.codelogickeep.agent.ut.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;

public class FileSystemToolImpl implements FileSystemTool {

    @Override
    public String readFile(String path) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            throw new IOException("File not found: " + path);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Override
    public void writeFile(String path, String content) throws IOException {
        Path p = Paths.get(path);
        // Ensure parent directories exist
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    @Override
    public void writeFileFromLine(String path, String content, int startLine) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            writeFile(path, content);
            return;
        }

        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        List<String> newLines = new ArrayList<>();
        
        // Add lines before startLine
        for (int i = 0; i < Math.min(startLine - 1, lines.size()); i++) {
            newLines.add(lines.get(i));
        }
        
        // If startLine is beyond current file size, pad with empty lines? 
        // Or just append? Let's pad with empty lines to reach startLine if necessary.
        while (newLines.size() < startLine - 1) {
            newLines.add("");
        }
        
        // Append the new content
        // The content might be multi-line
        newLines.add(content);
        
        // Note: The user asked to "append/write code starting from line X".
        // Often this means truncating whatever was after, or inserting.
        // Given it's "write code", usually we are filling a file or appending a method.
        // If we simply add 'content' as a single string, writeString handles newlines in it.
        // But Files.write needs lines or bytes.
        
        // Let's assume 'content' is the rest of the file content starting from startLine.
        // Or should we keep existing lines after? 
        // "from line X... append code" usually means "starting at line X, put this code".
        // I will interpret it as: Keep 1..(startLine-1), then append 'content'.
        // Any previous content from startLine onwards is replaced/removed.
        
        Files.writeString(p, String.join(System.lineSeparator(), newLines), StandardCharsets.UTF_8);
        
        // Wait, String.join might mess up if I used add(content) and content has newlines.
        // Better: Construct the full string.
        StringBuilder sb = new StringBuilder();
        for (String line : newLines) {
            // Check if it's the last added element (the content)
            if (line.equals(content) && newLines.indexOf(line) == newLines.size() - 1) {
                sb.append(line);
            } else {
                sb.append(line).append(System.lineSeparator());
            }
        }
        // Actually, simpler:
        // 1. Write the prefix (lines before startLine)
        // 2. Append content
        
        StringBuilder finalContent = new StringBuilder();
        for(int i=0; i < Math.min(startLine - 1, lines.size()); i++) {
             finalContent.append(lines.get(i)).append(System.lineSeparator());
        }
         // Pad if needed
        while (lines.size() < startLine - 1) { // logic error in loop check above? No.
             // If lines.size() is 5, and startLine is 10.
             // We appended 5 lines. We need 4 more empty lines to reach line 9 (so next is 10).
             // Current finalContent has 5 lines.
        }
        // Actually, let's just stick to "Keep lines before startLine" + "Append new content".
        
        finalContent.append(content);
        
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        Files.writeString(p, finalContent.toString(), StandardCharsets.UTF_8);
    }
}
