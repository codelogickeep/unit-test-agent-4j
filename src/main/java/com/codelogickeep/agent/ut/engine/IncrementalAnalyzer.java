package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.tools.GitDiffTool;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 增量分析器 - 整合 Git 差异检测与测试任务生成
 * 
 * 使用场景：
 * 1. 仅为变更的代码生成/更新测试
 * 2. 减少大型项目的处理时间
 * 3. 持续集成中的增量测试生成
 * 4. 变更影响范围分析
 */
@Slf4j
@RequiredArgsConstructor
public class IncrementalAnalyzer {

    private final GitDiffTool gitDiffTool;
    private final JavaParser javaParser = new JavaParser();

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
    
    // ==================== 变更影响范围分析 ====================
    
    /**
     * 分析变更代码的影响范围
     * 
     * @param repoPath 仓库路径
     * @param changedClasses 变更的类列表（全限定类名）
     * @return 影响范围分析结果
     */
    public ImpactAnalysisResult analyzeImpactScope(String repoPath, List<String> changedClasses) throws IOException {
        log.info("Analyzing impact scope for {} changed classes", changedClasses.size());
        long startTime = System.currentTimeMillis();
        
        // 1. 构建项目依赖图
        DependencyGraph graph = buildDependencyGraph(repoPath);
        
        // 2. 查找受影响的类
        Set<String> directlyAffected = new HashSet<>(changedClasses);
        Set<String> indirectlyAffected = new HashSet<>();
        
        for (String changedClass : changedClasses) {
            // 找到依赖此变更类的其他类
            Set<String> dependents = graph.getDependents(changedClass);
            indirectlyAffected.addAll(dependents);
        }
        
        // 移除直接变更的类
        indirectlyAffected.removeAll(directlyAffected);
        
        // 3. 识别可能需要更新的测试
        Set<String> affectedTests = findAffectedTests(repoPath, directlyAffected, indirectlyAffected);
        
        long elapsedMs = System.currentTimeMillis() - startTime;
        log.info("Impact analysis completed in {}ms: {} direct, {} indirect, {} tests affected",
                elapsedMs, directlyAffected.size(), indirectlyAffected.size(), affectedTests.size());
        
        return ImpactAnalysisResult.builder()
                .changedClasses(new ArrayList<>(directlyAffected))
                .affectedClasses(new ArrayList<>(indirectlyAffected))
                .affectedTests(new ArrayList<>(affectedTests))
                .dependencyGraph(graph)
                .analysisTimeMs(elapsedMs)
                .build();
    }
    
    /**
     * 构建项目依赖图
     */
    public DependencyGraph buildDependencyGraph(String repoPath) throws IOException {
        log.debug("Building dependency graph for: {}", repoPath);
        DependencyGraph graph = new DependencyGraph();
        
        Path srcMainJava = Paths.get(repoPath, "src", "main", "java");
        if (!Files.exists(srcMainJava)) {
            log.warn("Source directory not found: {}", srcMainJava);
            return graph;
        }
        
        try (Stream<Path> stream = Files.find(srcMainJava, 20,
                (p, attr) -> attr.isRegularFile() && p.toString().endsWith(".java"))) {
            
            List<Path> javaFiles = stream.collect(Collectors.toList());
            log.debug("Found {} Java files to analyze", javaFiles.size());
            
            for (Path file : javaFiles) {
                try {
                    analyzeFileDependencies(file, srcMainJava, graph);
                } catch (Exception e) {
                    log.debug("Failed to parse {}: {}", file.getFileName(), e.getMessage());
                }
            }
        }
        
        log.debug("Dependency graph built: {} classes, {} edges", 
                graph.getClassCount(), graph.getEdgeCount());
        return graph;
    }
    
    /**
     * 分析单个文件的依赖关系
     */
    private void analyzeFileDependencies(Path file, Path srcRoot, DependencyGraph graph) throws IOException {
        String content = Files.readString(file);
        ParseResult<CompilationUnit> parseResult = javaParser.parse(content);
        
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            return;
        }
        
        CompilationUnit cu = parseResult.getResult().get();
        
        // 获取当前类的全限定名
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        
        String className = file.getFileName().toString().replace(".java", "");
        String fullClassName = packageName.isEmpty() ? className : packageName + "." + className;
        
        graph.addClass(fullClassName);
        
        // 分析 import 语句
        for (ImportDeclaration importDecl : cu.getImports()) {
            String importedClass = importDecl.getNameAsString();
            if (!importDecl.isAsterisk() && !isJdkClass(importedClass)) {
                graph.addDependency(fullClassName, importedClass);
            }
        }
        
        // 分析方法调用和对象创建
        cu.findAll(ObjectCreationExpr.class).forEach(expr -> {
            String type = expr.getTypeAsString();
            String qualifiedType = resolveType(type, cu);
            if (qualifiedType != null && !isJdkClass(qualifiedType)) {
                graph.addDependency(fullClassName, qualifiedType);
            }
        });
    }
    
    /**
     * 解析类型名称为全限定名
     */
    private String resolveType(String typeName, CompilationUnit cu) {
        // 检查是否是已导入的类型
        for (ImportDeclaration importDecl : cu.getImports()) {
            if (!importDecl.isAsterisk()) {
                String importedClass = importDecl.getNameAsString();
                if (importedClass.endsWith("." + typeName)) {
                    return importedClass;
                }
            }
        }
        
        // 同一包下的类
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        
        if (!packageName.isEmpty()) {
            return packageName + "." + typeName;
        }
        
        return typeName;
    }
    
    /**
     * 判断是否为 JDK 类
     */
    private boolean isJdkClass(String className) {
        return className.startsWith("java.") || 
               className.startsWith("javax.") ||
               className.startsWith("sun.") ||
               className.startsWith("com.sun.");
    }
    
    /**
     * 查找受影响的测试文件
     */
    private Set<String> findAffectedTests(String repoPath, Set<String> directlyAffected, 
                                          Set<String> indirectlyAffected) throws IOException {
        Set<String> affectedTests = new HashSet<>();
        
        Path srcTestJava = Paths.get(repoPath, "src", "test", "java");
        if (!Files.exists(srcTestJava)) {
            return affectedTests;
        }
        
        Set<String> allAffected = new HashSet<>(directlyAffected);
        allAffected.addAll(indirectlyAffected);
        
        try (Stream<Path> stream = Files.find(srcTestJava, 20,
                (p, attr) -> attr.isRegularFile() && p.toString().endsWith("Test.java"))) {
            
            for (Path testFile : stream.collect(Collectors.toList())) {
                try {
                    String content = Files.readString(testFile);
                    
                    // 检查测试文件是否引用了任何受影响的类
                    for (String affectedClass : allAffected) {
                        String simpleClassName = affectedClass.substring(
                                affectedClass.lastIndexOf('.') + 1);
                        
                        // 检查导入语句或类引用
                        if (content.contains("import " + affectedClass) ||
                            content.contains(simpleClassName + " ") ||
                            content.contains(simpleClassName + ".") ||
                            content.contains("new " + simpleClassName)) {
                            
                            affectedTests.add(testFile.toString());
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to analyze test file {}: {}", testFile.getFileName(), e.getMessage());
                }
            }
        }
        
        return affectedTests;
    }
    
    // ==================== 依赖图数据结构 ====================
    
    /**
     * 类依赖图
     */
    @Data
    public static class DependencyGraph {
        /** 类 -> 它依赖的类集合 */
        private Map<String, Set<String>> dependencies = new HashMap<>();
        
        /** 类 -> 依赖它的类集合（反向索引） */
        private Map<String, Set<String>> dependents = new HashMap<>();
        
        public void addClass(String className) {
            dependencies.computeIfAbsent(className, k -> new HashSet<>());
            dependents.computeIfAbsent(className, k -> new HashSet<>());
        }
        
        public void addDependency(String fromClass, String toClass) {
            dependencies.computeIfAbsent(fromClass, k -> new HashSet<>()).add(toClass);
            dependents.computeIfAbsent(toClass, k -> new HashSet<>()).add(fromClass);
        }
        
        /**
         * 获取依赖某个类的所有类
         */
        public Set<String> getDependents(String className) {
            return dependents.getOrDefault(className, Collections.emptySet());
        }
        
        /**
         * 获取某个类依赖的所有类
         */
        public Set<String> getDependencies(String className) {
            return dependencies.getOrDefault(className, Collections.emptySet());
        }
        
        public int getClassCount() {
            return dependencies.size();
        }
        
        public int getEdgeCount() {
            return dependencies.values().stream().mapToInt(Set::size).sum();
        }
        
        /**
         * 生成依赖图摘要
         */
        public String toSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Dependency Graph Summary:\n");
            sb.append(String.format("  Classes: %d\n", getClassCount()));
            sb.append(String.format("  Dependencies: %d\n", getEdgeCount()));
            
            // 找出被依赖最多的类
            List<Map.Entry<String, Set<String>>> topDependents = dependents.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                    .limit(5)
                    .collect(Collectors.toList());
            
            if (!topDependents.isEmpty()) {
                sb.append("\n  Most depended-on classes:\n");
                for (Map.Entry<String, Set<String>> entry : topDependents) {
                    if (entry.getValue().size() > 0) {
                        sb.append(String.format("    %s: %d dependents\n", 
                                getSimpleName(entry.getKey()), entry.getValue().size()));
                    }
                }
            }
            
            return sb.toString();
        }
        
        private String getSimpleName(String fullName) {
            int lastDot = fullName.lastIndexOf('.');
            return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
        }
    }
    
    /**
     * 影响分析结果
     */
    @Data
    @Builder
    public static class ImpactAnalysisResult {
        /** 直接变更的类 */
        private List<String> changedClasses;
        
        /** 间接受影响的类（依赖变更类的其他类） */
        private List<String> affectedClasses;
        
        /** 可能需要更新的测试文件 */
        private List<String> affectedTests;
        
        /** 依赖图 */
        private DependencyGraph dependencyGraph;
        
        /** 分析耗时 */
        private long analysisTimeMs;
        
        public String toSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Impact Analysis Result ===\n");
            sb.append(String.format("Analysis Time: %dms\n", analysisTimeMs));
            sb.append(String.format("Changed Classes: %d\n", changedClasses.size()));
            sb.append(String.format("Affected Classes: %d\n", affectedClasses.size()));
            sb.append(String.format("Affected Tests: %d\n", affectedTests.size()));
            
            if (!changedClasses.isEmpty()) {
                sb.append("\nChanged Classes:\n");
                changedClasses.forEach(c -> sb.append("  [CHANGED] ").append(c).append("\n"));
            }
            
            if (!affectedClasses.isEmpty()) {
                sb.append("\nIndirectly Affected Classes:\n");
                affectedClasses.stream().limit(10).forEach(c -> 
                        sb.append("  [AFFECTED] ").append(c).append("\n"));
                if (affectedClasses.size() > 10) {
                    sb.append(String.format("  ... and %d more\n", affectedClasses.size() - 10));
                }
            }
            
            if (!affectedTests.isEmpty()) {
                sb.append("\nTests that may need updates:\n");
                affectedTests.stream().limit(10).forEach(t -> 
                        sb.append("  [TEST] ").append(Paths.get(t).getFileName()).append("\n"));
                if (affectedTests.size() > 10) {
                    sb.append(String.format("  ... and %d more\n", affectedTests.size() - 10));
                }
            }
            
            sb.append("==============================\n");
            return sb.toString();
        }
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
