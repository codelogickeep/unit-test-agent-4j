package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.exception.AgentToolException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.codelogickeep.agent.ut.exception.AgentToolException.ErrorCode.*;

/**
 * 变异测试工具 - 使用 PITest 评估测试质量
 * 
 * PITest 通过在代码中引入"变异"（小的代码修改）来测试测试用例的有效性。
 * 如果测试用例能检测到变异（杀死变异），说明测试质量好；
 * 如果变异存活，说明测试可能需要加强。
 */
@Slf4j
public class MutationTestTool implements AgentTool {

    private static final int DEFAULT_TIMEOUT_MINUTES = 10;

    /**
     * 执行 PITest 变异测试并返回结果摘要
     */
    @Tool("Run PITest mutation testing on a specific class and return mutation score summary.")
    public MutationTestResult runMutationTest(
            @P("Path to the Maven project root") String projectPath,
            @P("Target class to mutate (e.g., com.example.MyService)") String targetClass) throws IOException {
        log.info("Tool Input - runMutationTest: projectPath={}, targetClass={}", projectPath, targetClass);

        // 构建 PITest Maven 命令
        String command = buildPitestCommand(projectPath, targetClass);
        log.info("Executing PITest: {}", command);

        try {
            int exitCode = executeMavenCommand(projectPath, command);
            
            if (exitCode != 0) {
                log.warn("PITest execution returned non-zero exit code: {}", exitCode);
                // 仍然尝试解析报告，因为可能只是部分失败
            }

            // 解析 PITest 报告
            MutationTestResult result = parsePitestReport(projectPath, targetClass);
            log.info("Tool Output - runMutationTest: mutationScore={}%", result.getMutationScore());
            return result;

        } catch (Exception e) {
            throw new AgentToolException(EXTERNAL_TOOL_ERROR,
                    "Failed to run PITest mutation testing",
                    "Project: " + projectPath + ", Class: " + targetClass,
                    "Ensure PITest plugin is configured in pom.xml", e);
        }
    }

    /**
     * 检查项目是否配置了 PITest 插件
     */
    @Tool("Check if PITest plugin is configured in the project's pom.xml.")
    public String checkPitestConfiguration(
            @P("Path to the Maven project root") String projectPath) throws IOException {
        log.info("Tool Input - checkPitestConfiguration: projectPath={}", projectPath);

        Path pomPath = Paths.get(projectPath, "pom.xml");
        if (!Files.exists(pomPath)) {
            return "ERROR: pom.xml not found at " + projectPath;
        }

        String pomContent = Files.readString(pomPath);
        boolean hasPitest = pomContent.contains("pitest-maven") || pomContent.contains("org.pitest");

        StringBuilder result = new StringBuilder();
        result.append("PITest Configuration Check:\n");
        result.append("  Project: ").append(projectPath).append("\n");
        result.append("  PITest Plugin Configured: ").append(hasPitest ? "YES" : "NO").append("\n");

        if (!hasPitest) {
            result.append("\nTo add PITest, add this to your pom.xml <build><plugins> section:\n\n");
            result.append(getPitestPluginConfig());
        }

        String resultStr = result.toString();
        log.info("Tool Output - checkPitestConfiguration: configured={}", hasPitest);
        return resultStr;
    }

    /**
     * 获取变异测试的改进建议
     */
    @Tool("Get improvement suggestions based on mutation test results.")
    public String getMutationImprovementSuggestions(
            @P("Mutation test result as JSON or summary string") String mutationResultSummary,
            @P("Target class name") String targetClass) {
        log.info("Tool Input - getMutationImprovementSuggestions: targetClass={}", targetClass);

        StringBuilder suggestions = new StringBuilder();
        suggestions.append("Mutation Testing Improvement Suggestions for ").append(targetClass).append(":\n\n");

        // 基于常见的变异类型提供建议
        suggestions.append("1. SURVIVED Mutations - Common Causes & Fixes:\n");
        suggestions.append("   - Conditionals Boundary: Add boundary value tests (e.g., test value-1, value, value+1)\n");
        suggestions.append("   - Negated Conditionals: Ensure both true/false branches are tested\n");
        suggestions.append("   - Return Values: Verify return values are asserted, not just non-null checks\n");
        suggestions.append("   - Void Method Calls: Verify side effects of void methods\n\n");

        suggestions.append("2. General Test Quality Improvements:\n");
        suggestions.append("   - Add assertions for boundary conditions\n");
        suggestions.append("   - Test both success and failure paths\n");
        suggestions.append("   - Use parameterized tests for edge cases\n");
        suggestions.append("   - Verify exception messages, not just exception types\n\n");

        suggestions.append("3. Recommended Test Patterns:\n");
        suggestions.append("   - Given_When_Then structure\n");
        suggestions.append("   - One assertion focus per test\n");
        suggestions.append("   - Meaningful test names describing the scenario\n");

        String result = suggestions.toString();
        log.info("Tool Output - getMutationImprovementSuggestions: length={}", result.length());
        return result;
    }

    /**
     * 解析已存在的 PITest 报告
     */
    @Tool("Parse existing PITest mutation report to get detailed results.")
    public MutationTestResult parsePitestReport(
            @P("Path to the Maven project root") String projectPath,
            @P("Target class name (optional, for filtering)") String targetClass) throws IOException {
        log.info("Tool Input - parsePitestReport: projectPath={}, targetClass={}", projectPath, targetClass);

        Path reportsDir = Paths.get(projectPath, "target", "pit-reports");
        if (!Files.exists(reportsDir)) {
            throw new AgentToolException(RESOURCE_NOT_FOUND,
                    "PITest reports directory not found",
                    "Expected at: " + reportsDir.toAbsolutePath(),
                    "Run 'mvn org.pitest:pitest-maven:mutationCoverage' first");
        }

        // 找到最新的报告目录（PITest 按时间戳创建子目录）
        Path latestReportDir = findLatestReportDir(reportsDir);
        if (latestReportDir == null) {
            throw new AgentToolException(RESOURCE_NOT_FOUND,
                    "No PITest report directories found",
                    "Checked: " + reportsDir.toAbsolutePath(),
                    "Run mutation testing first");
        }

        // 解析 mutations.xml
        Path mutationsXml = latestReportDir.resolve("mutations.xml");
        if (!Files.exists(mutationsXml)) {
            // 尝试解析 HTML 报告
            return parseHtmlReport(latestReportDir, targetClass);
        }

        return parseXmlReport(mutationsXml, targetClass);
    }

    // ==================== 私有方法 ====================

    private String buildPitestCommand(String projectPath, String targetClass) {
        // 转换类名为 PITest 格式
        String targetClasses = targetClass.replace(".", "/") + ".class";
        String targetTests = targetClass + "Test";

        return String.format(
                "org.pitest:pitest-maven:mutationCoverage " +
                "-DtargetClasses=%s " +
                "-DtargetTests=%s " +
                "-DoutputFormats=XML,HTML " +
                "-DtimestampedReports=false",
                targetClass, targetTests);
    }

    private int executeMavenCommand(String projectPath, String command) throws IOException, InterruptedException {
        String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") 
                ? "mvn.cmd" : "mvn";

        // 构建完整的命令列表
        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(mvnCmd);
        for (String arg : command.split(" ")) {
            if (!arg.trim().isEmpty()) {
                fullCommand.add(arg.trim());
            }
        }

        ProcessBuilder pb = new ProcessBuilder();
        pb.command(fullCommand);
        pb.directory(new File(projectPath));
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // 读取输出
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("PITest: {}", line);
            }
        }

        boolean finished = process.waitFor(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("PITest execution timed out after " + DEFAULT_TIMEOUT_MINUTES + " minutes");
        }

        return process.exitValue();
    }

    private Path findLatestReportDir(Path reportsDir) throws IOException {
        try (Stream<Path> stream = Files.list(reportsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .max((p1, p2) -> {
                        try {
                            return Files.getLastModifiedTime(p1).compareTo(Files.getLastModifiedTime(p2));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .orElse(null);
        }
    }

    private MutationTestResult parseXmlReport(Path mutationsXml, String targetClass) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(mutationsXml.toFile());
            doc.getDocumentElement().normalize();

            NodeList mutations = doc.getElementsByTagName("mutation");

            int totalMutations = 0;
            int killedMutations = 0;
            int survivedMutations = 0;
            int noCoverageMutations = 0;
            List<MutationDetail> details = new ArrayList<>();
            Map<String, Integer> mutatorCounts = new HashMap<>();

            for (int i = 0; i < mutations.getLength(); i++) {
                Element mutation = (Element) mutations.item(i);
                
                String mutatedClass = getElementText(mutation, "mutatedClass");
                
                // 如果指定了目标类，只统计该类
                if (targetClass != null && !targetClass.isEmpty() && !mutatedClass.equals(targetClass)) {
                    continue;
                }

                totalMutations++;
                String status = mutation.getAttribute("status");
                String mutator = getElementText(mutation, "mutator");
                
                mutatorCounts.merge(mutator, 1, Integer::sum);

                switch (status) {
                    case "KILLED":
                        killedMutations++;
                        break;
                    case "SURVIVED":
                        survivedMutations++;
                        details.add(MutationDetail.builder()
                                .mutatedClass(mutatedClass)
                                .mutatedMethod(getElementText(mutation, "mutatedMethod"))
                                .mutator(simplifyMutatorName(mutator))
                                .lineNumber(Integer.parseInt(getElementText(mutation, "lineNumber")))
                                .status(status)
                                .description(getElementText(mutation, "description"))
                                .build());
                        break;
                    case "NO_COVERAGE":
                        noCoverageMutations++;
                        break;
                }
            }

            double mutationScore = totalMutations > 0 
                    ? (double) killedMutations / totalMutations * 100 
                    : 0;

            return MutationTestResult.builder()
                    .targetClass(targetClass)
                    .totalMutations(totalMutations)
                    .killedMutations(killedMutations)
                    .survivedMutations(survivedMutations)
                    .noCoverageMutations(noCoverageMutations)
                    .mutationScore(Math.round(mutationScore * 10) / 10.0)
                    .survivedDetails(details)
                    .mutatorBreakdown(mutatorCounts)
                    .build();

        } catch (Exception e) {
            throw new AgentToolException(PARSING_ERROR,
                    "Failed to parse PITest XML report",
                    "File: " + mutationsXml.toAbsolutePath(),
                    e.getMessage(), e);
        }
    }

    private MutationTestResult parseHtmlReport(Path reportDir, String targetClass) throws IOException {
        // HTML 报告解析是备选方案，返回基本信息
        log.warn("XML report not found, HTML parsing is limited");
        
        return MutationTestResult.builder()
                .targetClass(targetClass)
                .totalMutations(0)
                .killedMutations(0)
                .survivedMutations(0)
                .noCoverageMutations(0)
                .mutationScore(0)
                .survivedDetails(new ArrayList<>())
                .mutatorBreakdown(new HashMap<>())
                .build();
    }

    private String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return "";
    }

    private String simplifyMutatorName(String fullMutatorName) {
        // org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator
        // -> ConditionalsBoundary
        if (fullMutatorName == null || fullMutatorName.isEmpty()) {
            return "Unknown";
        }
        int lastDot = fullMutatorName.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? fullMutatorName.substring(lastDot + 1) : fullMutatorName;
        return simpleName.replace("Mutator", "");
    }

    private String getPitestPluginConfig() {
        return """
            <plugin>
                <groupId>org.pitest</groupId>
                <artifactId>pitest-maven</artifactId>
                <version>1.15.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.pitest</groupId>
                        <artifactId>pitest-junit5-plugin</artifactId>
                        <version>1.2.1</version>
                    </dependency>
                </dependencies>
                <configuration>
                    <outputFormats>
                        <outputFormat>XML</outputFormat>
                        <outputFormat>HTML</outputFormat>
                    </outputFormats>
                    <timestampedReports>false</timestampedReports>
                </configuration>
            </plugin>
            """;
    }

    // ==================== 数据类 ====================

    @Data
    @Builder
    public static class MutationTestResult {
        private String targetClass;
        private int totalMutations;
        private int killedMutations;
        private int survivedMutations;
        private int noCoverageMutations;
        private double mutationScore; // 百分比
        private List<MutationDetail> survivedDetails;
        private Map<String, Integer> mutatorBreakdown;

        public boolean isHighQuality() {
            return mutationScore >= 80;
        }

        public String toAgentMessage() {
            StringBuilder sb = new StringBuilder();
            sb.append("Mutation Test Results:\n");
            sb.append(String.format("  Target Class: %s\n", targetClass));
            sb.append(String.format("  Mutation Score: %.1f%% (%s)\n", 
                    mutationScore, isHighQuality() ? "GOOD" : "NEEDS IMPROVEMENT"));
            sb.append(String.format("  Total Mutations: %d\n", totalMutations));
            sb.append(String.format("    - Killed: %d\n", killedMutations));
            sb.append(String.format("    - Survived: %d\n", survivedMutations));
            sb.append(String.format("    - No Coverage: %d\n", noCoverageMutations));

            if (!survivedDetails.isEmpty()) {
                sb.append("\nSurvived Mutations (need stronger tests):\n");
                for (MutationDetail detail : survivedDetails.stream().limit(10).collect(Collectors.toList())) {
                    sb.append(String.format("  - Line %d: %s in %s\n",
                            detail.getLineNumber(), detail.getMutator(), detail.getMutatedMethod()));
                    if (detail.getDescription() != null && !detail.getDescription().isEmpty()) {
                        sb.append(String.format("    Description: %s\n", detail.getDescription()));
                    }
                }
                if (survivedDetails.size() > 10) {
                    sb.append(String.format("  ... and %d more\n", survivedDetails.size() - 10));
                }
            }

            return sb.toString();
        }
    }

    @Data
    @Builder
    public static class MutationDetail {
        private String mutatedClass;
        private String mutatedMethod;
        private String mutator;
        private int lineNumber;
        private String status;
        private String description;
    }
}
