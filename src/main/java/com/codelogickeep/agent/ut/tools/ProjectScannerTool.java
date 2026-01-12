package com.codelogickeep.agent.ut.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tool for scanning project directories to find core source classes.
 * Excludes test classes, data classes (DTO/VO/TO), and infrastructure classes.
 */
public class ProjectScannerTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(ProjectScannerTool.class);

    // Built-in exclude patterns for ERP projects
    private static final List<Pattern> DEFAULT_EXCLUDE_PATTERNS = List.of(
            // Test classes
            Pattern.compile(".*Test\\.java$"),
            Pattern.compile(".*Tests\\.java$"),
            Pattern.compile(".*IT\\.java$"),
            Pattern.compile(".*TestCase\\.java$"),
            Pattern.compile(".*/src/test/.*"),
            // Data classes
            Pattern.compile(".*/dao/.*Criteria\\.java$"),
            Pattern.compile(".*/domain/.*\\.java$"),
            Pattern.compile(".*/dto/.*\\.java$"),
            Pattern.compile(".*/to/.*TO\\.java$"),
            Pattern.compile(".*/vo/.*\\.java$"),
            Pattern.compile(".*/pojo/.*\\.java$"),
            Pattern.compile(".*DTO\\.java$"),
            Pattern.compile(".*VO\\.java$"),
            Pattern.compile(".*TO\\.java$"),
            Pattern.compile(".*Enum\\.java$"),
            Pattern.compile(".*Constants\\.java$"),
            // Infrastructure classes
            Pattern.compile(".*/repo/.*Repo\\.java$"),
            Pattern.compile(".*/repo/.*RepoImpl\\.java$"),
            Pattern.compile(".*/dao/.*DAO\\.java$"),
            Pattern.compile(".*/assembler/.*ConditionSpec\\.java$"),
            // Resources and third-party
            Pattern.compile(".*/src/main/resources/.*"),
            Pattern.compile(".*/org/apache/dubbo/.*")
    );

    @Tool("Scan project directory and return list of core source classes (excluding tests, DTOs, VOs, etc.)")
    public String scanProjectClasses(
            @P("Project root directory path") String projectPath,
            @P("Additional exclude patterns (comma-separated regex), e.g., '.*Util,.*Config'. Leave empty for defaults only.") String excludePatterns
    ) throws IOException {
        log.info("Tool Input - scanProjectClasses: projectPath={}, excludePatterns={}", projectPath, excludePatterns);

        Path root = Paths.get(projectPath);
        if (!Files.exists(root)) {
            return "ERROR: Project path does not exist: " + projectPath;
        }

        // Build exclude pattern list
        List<Pattern> allPatterns = new ArrayList<>(DEFAULT_EXCLUDE_PATTERNS);
        if (excludePatterns != null && !excludePatterns.trim().isEmpty()) {
            for (String p : excludePatterns.split(",")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    allPatterns.add(Pattern.compile(trimmed));
                }
            }
        }

        // Find src/main/java directory
        Path srcMain = root.resolve("src/main/java");
        if (!Files.exists(srcMain)) {
            // Try to find it in subdirectories (multi-module project)
            srcMain = root;
        }

        List<String> coreClasses = new ArrayList<>();
        try (Stream<Path> stream = Files.find(srcMain, 20,
                (p, attr) -> attr.isRegularFile() && p.toString().endsWith(".java"))) {

            coreClasses = stream
                    .map(Path::toString)
                    .map(s -> s.replace('\\', '/')) // Normalize path separators
                    .filter(path -> !isExcluded(path, allPatterns))
                    .collect(Collectors.toList());
        }

        if (coreClasses.isEmpty()) {
            return "No core source classes found in: " + projectPath;
        }

        StringBuilder result = new StringBuilder();
        result.append("Found ").append(coreClasses.size()).append(" core source classes:\n");
        for (String cls : coreClasses) {
            // Convert to relative path
            String relativePath = cls.replace(root.toString().replace('\\', '/'), "").replaceFirst("^/", "");
            result.append("  - ").append(relativePath).append("\n");
        }

        String finalResult = result.toString();
        log.info("Tool Output - scanProjectClasses: count={}", coreClasses.size());
        return finalResult;
    }

    @Tool("Get list of core source class paths as raw list (for batch processing)")
    public List<String> getSourceClassPaths(
            @P("Project root directory path") String projectPath,
            @P("Additional exclude patterns (comma-separated regex)") String excludePatterns
    ) throws IOException {
        log.info("Tool Input - getSourceClassPaths: projectPath={}", projectPath);

        Path root = Paths.get(projectPath);
        if (!Files.exists(root)) {
            return List.of();
        }

        List<Pattern> allPatterns = new ArrayList<>(DEFAULT_EXCLUDE_PATTERNS);
        if (excludePatterns != null && !excludePatterns.trim().isEmpty()) {
            for (String p : excludePatterns.split(",")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    allPatterns.add(Pattern.compile(trimmed));
                }
            }
        }

        Path srcMain = root.resolve("src/main/java");
        if (!Files.exists(srcMain)) {
            srcMain = root;
        }

        try (Stream<Path> stream = Files.find(srcMain, 20,
                (p, attr) -> attr.isRegularFile() && p.toString().endsWith(".java"))) {

            List<String> result = stream
                    .map(Path::toString)
                    .map(s -> s.replace('\\', '/'))
                    .filter(path -> !isExcluded(path, allPatterns))
                    .collect(Collectors.toList());
            log.info("Tool Output - getSourceClassPaths: count={}", result.size());
            return result;
        }
    }

    private boolean isExcluded(String path, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }
}
