package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.tools.GitDiffTool;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 增量分析器 - 整合 Git 差异检测与测试任务生成
 * 
 * 使用场景：
 * 1. 仅为变更的代码生成/更新测试
 * 2. 减少大型项目的处理时间
 * 3. 持续集成中的增量测试生成
 */
@Slf4j
@RequiredArgsConstructor
public class IncrementalAnalyzer {

    private final GitDiffTool gitDiffTool;

    /**
     * 分析增量变更并生成需要测试的类列表
     * 
     * @param repoPath 仓库路径
     * @param options 增量分析选项
     * @return 需要生成/更新测试的类列表
     */
    public IncrementalAnalysisResult analyze(String repoPath, IncrementalOptions options) throws IOException {
        log.info("Starting incremental analysis for repository: {}", repoPath);
        log.info("Options: mode={}, baseRef={}, targetRef={}", 
                options.getMode(), options.getBaseRef(), options.getTargetRef());

        List<GitDiffTool.ChangedJavaClass> changedClasses;

        switch (options.getMode()) {
            case UNCOMMITTED:
                // 默认模式：检测所有未提交的更改
                changedClasses = gitDiffTool.analyzeChangedSourceClasses(repoPath);
                break;
                
            case STAGED_ONLY:
                // 仅检测暂存区的更改
                changedClasses = filterStagedOnly(repoPath);
                break;
                
            case COMPARE_REFS:
                // 比较两个 Git 引用
                changedClasses = analyzeRefComparison(repoPath, options.getBaseRef(), options.getTargetRef());
                break;
                
            default:
                changedClasses = gitDiffTool.analyzeChangedSourceClasses(repoPath);
        }

        // 过滤掉排除的模式
        if (options.getExcludePatterns() != null && !options.getExcludePatterns().isEmpty()) {
            changedClasses = filterByPatterns(changedClasses, options.getExcludePatterns());
        }

        // 构建结果
        List<TestGenerationTask> tasks = changedClasses.stream()
                .map(c -> buildTask(repoPath, c))
                .collect(Collectors.toList());

        IncrementalAnalysisResult result = IncrementalAnalysisResult.builder()
                .totalChangedFiles(changedClasses.size())
                .tasks(tasks)
                .mode(options.getMode())
                .build();

        log.info("Incremental analysis complete: {} classes need test generation/update", tasks.size());
        return result;
    }

    /**
     * 仅过滤暂存区的变更
     */
    private List<GitDiffTool.ChangedJavaClass> filterStagedOnly(String repoPath) throws IOException {
        // 获取暂存区状态
        String stagedChanges = gitDiffTool.getStagedChanges(repoPath);
        
        // 解析暂存区的 Java 文件
        List<GitDiffTool.ChangedJavaClass> result = new ArrayList<>();
        for (String line : stagedChanges.split("\n")) {
            line = line.trim();
            if (line.endsWith(".java") && !line.contains("src/test/java")) {
                // 从行中提取文件路径
                if (line.startsWith("  ")) {
                    String filePath = line.trim();
                    result.add(GitDiffTool.ChangedJavaClass.builder()
                            .filePath(filePath)
                            .className(extractClassName(filePath))
                            .changeType("STAGED")
                            .needsTestUpdate(true)
                            .build());
                }
            }
        }
        return result;
    }

    /**
     * 分析两个引用之间的变更
     */
    private List<GitDiffTool.ChangedJavaClass> analyzeRefComparison(String repoPath, String baseRef, String targetRef) throws IOException {
        String changes = gitDiffTool.getChangesBetweenRefs(repoPath, baseRef, targetRef);
        
        List<GitDiffTool.ChangedJavaClass> result = new ArrayList<>();
        boolean inChangedSection = false;
        
        for (String line : changes.split("\n")) {
            if (line.contains("Changed Java Files:")) {
                inChangedSection = true;
                continue;
            }
            if (inChangedSection && line.trim().startsWith("[")) {
                // 解析 [MODIFY] path/to/File.java 格式
                String trimmed = line.trim();
                int endBracket = trimmed.indexOf("]");
                if (endBracket > 0) {
                    String changeType = trimmed.substring(1, endBracket);
                    String filePath = trimmed.substring(endBracket + 1).trim();
                    
                    if (filePath.endsWith(".java") && 
                        (filePath.contains("src/main/java") || filePath.contains("src\\main\\java"))) {
                        result.add(GitDiffTool.ChangedJavaClass.builder()
                                .filePath(filePath)
                                .className(extractClassName(filePath))
                                .changeType(changeType)
                                .needsTestUpdate(true)
                                .build());
                    }
                }
            }
        }
        return result;
    }

    /**
     * 根据排除模式过滤
     */
    private List<GitDiffTool.ChangedJavaClass> filterByPatterns(
            List<GitDiffTool.ChangedJavaClass> classes, 
            List<String> excludePatterns) {
        return classes.stream()
                .filter(c -> {
                    for (String pattern : excludePatterns) {
                        if (matchesPattern(c.getFilePath(), pattern) || 
                            matchesPattern(c.getClassName(), pattern)) {
                            log.debug("Excluding {} due to pattern: {}", c.getClassName(), pattern);
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    private boolean matchesPattern(String value, String pattern) {
        // 简单的通配符匹配
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return value.matches(".*" + regex + ".*");
    }

    private TestGenerationTask buildTask(String repoPath, GitDiffTool.ChangedJavaClass changedClass) {
        Path sourcePath = Paths.get(repoPath, changedClass.getFilePath());
        Path expectedTestPath = deriveTestPath(repoPath, changedClass.getFilePath());
        boolean testExists = Files.exists(expectedTestPath);
        
        return TestGenerationTask.builder()
                .sourceFilePath(sourcePath.toString())
                .className(changedClass.getClassName())
                .changeType(changedClass.getChangeType())
                .expectedTestPath(expectedTestPath.toString())
                .testExists(testExists)
                .action(testExists ? TaskAction.UPDATE : TaskAction.CREATE)
                .build();
    }

    private Path deriveTestPath(String repoPath, String sourceFilePath) {
        String normalized = sourceFilePath.replace("\\", "/");
        String testPath = normalized.replace("src/main/java", "src/test/java");
        
        // 添加 Test 后缀
        if (testPath.endsWith(".java")) {
            testPath = testPath.replace(".java", "Test.java");
        }
        
        return Paths.get(repoPath, testPath);
    }

    private String extractClassName(String filePath) {
        String normalized = filePath.replace("\\", "/");
        int srcMainJava = normalized.indexOf("src/main/java/");
        if (srcMainJava >= 0) {
            String classPath = normalized.substring(srcMainJava + "src/main/java/".length());
            return classPath.replace("/", ".").replace(".java", "");
        }
        return Paths.get(filePath).getFileName().toString().replace(".java", "");
    }

    // ==================== 数据类 ====================

    public enum IncrementalMode {
        /** 检测所有未提交的更改（默认） */
        UNCOMMITTED,
        /** 仅检测暂存区的更改 */
        STAGED_ONLY,
        /** 比较两个 Git 引用 */
        COMPARE_REFS
    }

    public enum TaskAction {
        /** 创建新测试 */
        CREATE,
        /** 更新现有测试 */
        UPDATE
    }

    @Data
    @Builder
    public static class IncrementalOptions {
        @Builder.Default
        private IncrementalMode mode = IncrementalMode.UNCOMMITTED;
        
        /** 基准引用（用于 COMPARE_REFS 模式） */
        private String baseRef;
        
        /** 目标引用（用于 COMPARE_REFS 模式，默认 HEAD） */
        @Builder.Default
        private String targetRef = "HEAD";
        
        /** 排除模式列表 */
        private List<String> excludePatterns;
    }

    @Data
    @Builder
    public static class IncrementalAnalysisResult {
        private int totalChangedFiles;
        private List<TestGenerationTask> tasks;
        private IncrementalMode mode;

        public boolean hasChanges() {
            return totalChangedFiles > 0;
        }

        public String toSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Incremental Analysis Result:\n");
            sb.append(String.format("  Mode: %s\n", mode));
            sb.append(String.format("  Total Changed Files: %d\n", totalChangedFiles));
            sb.append(String.format("  Tasks to Execute: %d\n", tasks.size()));
            
            if (!tasks.isEmpty()) {
                long createCount = tasks.stream().filter(t -> t.getAction() == TaskAction.CREATE).count();
                long updateCount = tasks.stream().filter(t -> t.getAction() == TaskAction.UPDATE).count();
                sb.append(String.format("    - New tests to create: %d\n", createCount));
                sb.append(String.format("    - Existing tests to update: %d\n", updateCount));
                
                sb.append("\n  Tasks:\n");
                for (TestGenerationTask task : tasks) {
                    sb.append(String.format("    [%s] %s (%s)\n", 
                            task.getAction(), task.getClassName(), task.getChangeType()));
                }
            }
            
            return sb.toString();
        }
    }

    @Data
    @Builder
    public static class TestGenerationTask {
        private String sourceFilePath;
        private String className;
        private String changeType;
        private String expectedTestPath;
        private boolean testExists;
        private TaskAction action;
    }
}
