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

/**
 * Tool for discovering existing test classes for a given source class.
 */
public class TestDiscoveryTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(TestDiscoveryTool.class);

    // Common test class naming conventions
    private static final String[] TEST_SUFFIXES = {"Test", "Tests", "TestCase"};
    private static final String[] TEST_PREFIXES = {"Test"};

    @Tool("Find existing test classes for a given source class")
    public String findTestClasses(
            @P("Path to the source Java file") String sourceFilePath,
            @P("Project root directory") String projectRoot
    ) throws IOException {
        log.info("Tool Input - findTestClasses: sourceFilePath={}, projectRoot={}", sourceFilePath, projectRoot);

        Path sourcePath = Paths.get(sourceFilePath);
        String fileName = sourcePath.getFileName().toString();
        if (!fileName.endsWith(".java")) {
            return "ERROR: Not a Java file: " + sourceFilePath;
        }

        String className = fileName.replace(".java", "");

        // Convert source path to test path
        String sourcePathStr = sourceFilePath.replace('\\', '/');
        String testBasePath = sourcePathStr.replace("/src/main/java/", "/src/test/java/");
        Path testDir = Paths.get(testBasePath).getParent();

        List<String> foundTests = new ArrayList<>();

        // Check for test classes with different naming conventions
        for (String suffix : TEST_SUFFIXES) {
            String testFileName = className + suffix + ".java";
            Path testPath = testDir.resolve(testFileName);
            if (Files.exists(testPath)) {
                foundTests.add(testPath.toString().replace('\\', '/'));
            }
        }

        for (String prefix : TEST_PREFIXES) {
            String testFileName = prefix + className + ".java";
            Path testPath = testDir.resolve(testFileName);
            if (Files.exists(testPath)) {
                foundTests.add(testPath.toString().replace('\\', '/'));
            }
        }

        // Also search in the test directory for any file containing the class name
        if (Files.exists(testDir)) {
            try (var stream = Files.list(testDir)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                      .filter(p -> {
                          String name = p.getFileName().toString();
                          return name.contains(className) && !foundTests.contains(p.toString().replace('\\', '/'));
                      })
                      .forEach(p -> foundTests.add(p.toString().replace('\\', '/')));
            }
        }

        if (foundTests.isEmpty()) {
            return "No existing test classes found for: " + className + "\nExpected test directory: " + testDir;
        }

        StringBuilder result = new StringBuilder();
        result.append("Found ").append(foundTests.size()).append(" test class(es) for ").append(className).append(":\n");
        for (String test : foundTests) {
            // Convert to relative path
            String relativePath = test;
            if (projectRoot != null) {
                relativePath = test.replace(projectRoot.replace('\\', '/'), "").replaceFirst("^/", "");
            }
            result.append("  - ").append(relativePath).append("\n");
        }

        String finalResult = result.toString();
        log.info("Tool Output - findTestClasses: count={}", foundTests.size());
        return finalResult;
    }

    @Tool("Get the expected test file path for a source class (creates path even if file doesn't exist)")
    public String getExpectedTestPath(
            @P("Path to the source Java file") String sourceFilePath
    ) {
        log.info("Tool Input - getExpectedTestPath: sourceFilePath={}", sourceFilePath);

        String sourcePathStr = sourceFilePath.replace('\\', '/');
        String testPath = sourcePathStr
                .replace("/src/main/java/", "/src/test/java/")
                .replace(".java", "Test.java");

        boolean exists = Files.exists(Paths.get(testPath));
        String finalResult = testPath + (exists ? " (exists)" : " (not exists)");
        log.info("Tool Output - getExpectedTestPath: {}", finalResult);
        return finalResult;
    }

    @Tool("Check if a test class exists for the given source class")
    public boolean hasTestClass(
            @P("Path to the source Java file") String sourceFilePath
    ) {
        log.info("Tool Input - hasTestClass: sourceFilePath={}", sourceFilePath);

        String sourcePathStr = sourceFilePath.replace('\\', '/');
        String testBasePath = sourcePathStr.replace("/src/main/java/", "/src/test/java/");
        Path testDir = Paths.get(testBasePath).getParent();

        String fileName = Paths.get(sourceFilePath).getFileName().toString();
        String className = fileName.replace(".java", "");

        // Check common naming conventions
        for (String suffix : TEST_SUFFIXES) {
            Path testPath = testDir.resolve(className + suffix + ".java");
            if (Files.exists(testPath)) {
                log.info("Tool Output - hasTestClass: true ({})", testPath);
                return true;
            }
        }

        log.info("Tool Output - hasTestClass: false");
        return false;
    }
}
