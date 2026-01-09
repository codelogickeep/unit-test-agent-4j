package com.codelogickeep.agent.ut.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Pattern;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileSystemTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(FileSystemTool.class);
    private Path projectRoot = Paths.get(".").toAbsolutePath().normalize();
    private boolean interactive = false;
    private static final Scanner scanner = new Scanner(System.in);

    public void setProjectRoot(String rootPath) {
        if (rootPath != null) {
            this.projectRoot = Paths.get(rootPath).toAbsolutePath().normalize();
            log.info("FileSystemTool project root locked to: {}", projectRoot);
        }
    }

    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
        if (interactive) {
            log.info("FileSystemTool interactive mode enabled");
        }
    }

    private Path resolveSafePath(String pathStr) throws IOException {
        if (pathStr == null || pathStr.trim().isEmpty() || "null".equalsIgnoreCase(pathStr)) {
            throw new IOException("Path is null or empty. Please provide a valid file path relative to the project root.");
        }
        Path p = Paths.get(pathStr);
        if (!p.isAbsolute()) {
            p = projectRoot.resolve(p);
        }
        p = p.normalize();
        if (!p.startsWith(projectRoot)) {
            throw new IOException("Access denied: Path is outside of project root: " + pathStr);
        }
        return p;
    }

    private boolean confirmWrite(String path, String content, String operation) {
        if (!interactive) {
            return true;
        }

        System.out.println();
        System.out.println("═".repeat(60));
        System.out.println("📝 INTERACTIVE CONFIRMATION REQUIRED");
        System.out.println("═".repeat(60));
        System.out.println("Operation: " + operation);
        System.out.println("File: " + path);
        System.out.println("─".repeat(60));

        // Show preview (first 30 lines or 2000 chars)
        String preview = content;
        String[] lines = content.split("\n");
        if (lines.length > 30) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                sb.append(lines[i]).append("\n");
            }
            sb.append("... (").append(lines.length - 30).append(" more lines)");
            preview = sb.toString();
        } else if (content.length() > 2000) {
            preview = content.substring(0, 2000) + "\n... (truncated)";
        }

        System.out.println("Content Preview:");
        System.out.println("─".repeat(60));
        System.out.println(preview);
        System.out.println("─".repeat(60));
        System.out.println();
        System.out.print("Proceed with this write? [Y/n/v(iew full)]: ");
        System.out.flush();

        String input = scanner.nextLine().trim().toLowerCase();

        if ("v".equals(input)) {
            System.out.println("\n=== FULL CONTENT ===\n");
            System.out.println(content);
            System.out.println("\n=== END OF CONTENT ===\n");
            System.out.print("Proceed with this write? [Y/n]: ");
            System.out.flush();
            input = scanner.nextLine().trim().toLowerCase();
        }

        boolean confirmed = input.isEmpty() || "y".equals(input) || "yes".equals(input);
        if (confirmed) {
            System.out.println("✓ Confirmed. Writing file...");
        } else {
            System.out.println("✗ Cancelled by user.");
        }
        System.out.println();

        return confirmed;
    }

    @Tool("Replace the first occurrence of a specific string with a new string in a file. Use this for precise code fixes in Java or POM files.")
    public String searchReplace(@P("Path to the file (relative to project root)") String path,
                              @P("The exact string to be replaced") String oldString,
                              @P("The new string to replace with") String newString) throws IOException {
        log.info("Tool Input - searchReplace: path={}, oldString length={}, newString length={}", path,
                oldString != null ? oldString.length() : "null",
                newString != null ? newString.length() : "null");
        try {
            Path p = resolveSafePath(path);
            if (!Files.exists(p)) {
                return "ERROR: File not found at path: " + path + ". Ensure you are using a path relative to the project root: " + projectRoot;
            }
            String content = Files.readString(p, StandardCharsets.UTF_8);
            if (!content.contains(oldString)) {
                return "ERROR: oldString NOT FOUND. Please use 'readFile' to get the exact content first.";
            }
            String newContent = content.replaceFirst(Pattern.quote(oldString), newString);

            if (interactive) {
                // Show diff-like preview
                System.out.println();
                System.out.println("═".repeat(60));
                System.out.println("📝 SEARCH & REPLACE CONFIRMATION");
                System.out.println("═".repeat(60));
                System.out.println("File: " + path);
                System.out.println("─".repeat(60));
                System.out.println("OLD (to be replaced):");
                System.out.println(oldString.length() > 500 ? oldString.substring(0, 500) + "..." : oldString);
                System.out.println("─".repeat(60));
                System.out.println("NEW (replacement):");
                System.out.println(newString.length() > 500 ? newString.substring(0, 500) + "..." : newString);
                System.out.println("─".repeat(60));
                System.out.print("Proceed with this replacement? [Y/n]: ");
                System.out.flush();

                String input = scanner.nextLine().trim().toLowerCase();
                if (!input.isEmpty() && !"y".equals(input) && !"yes".equals(input)) {
                    return "CANCELLED: User declined the replacement.";
                }
                System.out.println("✓ Confirmed. Applying replacement...");
            }

            Files.writeString(p, newContent, StandardCharsets.UTF_8);
            return "SUCCESS: File updated: " + path;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool("Check if a file exists.")
    public boolean fileExists(@P("Path to the file to check") String path) {
        log.info("Tool Input - fileExists: path={}", path);
        try {
            Path p = resolveSafePath(path);
            return Files.exists(p) && Files.isRegularFile(p);
        } catch (Exception e) {
            return false;
        }
    }

    @Tool("Read the content of a file (e.g., source code, pom.xml, or error logs).")
    public String readFile(@P("Path to the file to read") String path) throws IOException {
        log.info("Tool Input - readFile: path={}", path);
        try {
            Path p = resolveSafePath(path);
            if (!Files.exists(p)) {
                throw new IOException("File not found: " + path);
            }
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    @Tool("Write content to a file. Useful for creating new test classes.")
    public String writeFile(@P("Path to the file") String path, @P("Full content to write") String content) throws IOException {
        log.info("Tool Input - writeFile: path={}, content length={}", path, content != null ? content.length() : "null");
        try {
            Path p = resolveSafePath(path);

            // Interactive confirmation
            if (!confirmWrite(path, content, Files.exists(p) ? "OVERWRITE FILE" : "CREATE NEW FILE")) {
                return "CANCELLED: User declined to write file: " + path;
            }

            // Ensure parent directories exist
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return "SUCCESS: File written: " + path;
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    @Tool("Replace content in a file starting from a specific line number.")
    public String writeFileFromLine(@P("Path to the file") String path, @P("New content to write from the start line") String content, @P("Line number (1-based) to start writing from") int startLine) throws IOException {
        log.info("Tool Input - writeFileFromLine: path={}, startLine={}, content length={}", path, startLine, content != null ? content.length() : "null");
        try {
            Path p = resolveSafePath(path);
            if (!Files.exists(p)) {
                return writeFile(path, content);
            }

            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            StringBuilder finalContent = new StringBuilder();
            for (int i = 0; i < Math.min(startLine - 1, lines.size()); i++) {
                finalContent.append(lines.get(i)).append(System.lineSeparator());
            }
            // Pad if needed
            while (lines.size() < startLine - 1) {
                finalContent.append(System.lineSeparator());
                lines.add("");
            }

            finalContent.append(content);

            // Interactive confirmation
            if (!confirmWrite(path, finalContent.toString(), "MODIFY FILE FROM LINE " + startLine)) {
                return "CANCELLED: User declined to modify file: " + path;
            }

            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, finalContent.toString(), StandardCharsets.UTF_8);
            return "SUCCESS: File modified from line " + startLine + ": " + path;
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }
}
