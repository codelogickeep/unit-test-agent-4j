package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.exception.AgentToolException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.codelogickeep.agent.ut.exception.AgentToolException.ErrorCode.*;

/**
 * Git 差异分析工具 - 提供灵活的增量检测能力
 * 
 * 设计原则：
 * - 不硬编码任何分支名称（如 main、master）
 * - 默认比较 HEAD（未提交的更改）
 * - 支持用户指定任意基准 ref
 */
@Slf4j
public class GitDiffTool implements AgentTool {

    /**
     * 获取工作区所有未提交的更改（包括未暂存和已暂存）
     * 这是最常用的场景：查看当前工作目录相对于最近一次提交的所有变更
     */
    @Tool("Get all uncommitted changes in the working directory (both staged and unstaged). Returns a list of changed Java files.")
    public String getUncommittedChanges(
            @P("Path to the Git repository root") String repoPath) throws IOException {
        log.info("Tool Input - getUncommittedChanges: repoPath={}", repoPath);
        
        try (Git git = Git.open(new File(repoPath))) {
            Status status = git.status().call();
            
            Set<String> allChangedFiles = new HashSet<>();
            
            // 未暂存的修改
            allChangedFiles.addAll(status.getModified());
            // 已暂存的修改
            allChangedFiles.addAll(status.getChanged());
            // 新增文件（未暂存）
            allChangedFiles.addAll(status.getUntracked());
            // 新增文件（已暂存）
            allChangedFiles.addAll(status.getAdded());
            // 删除的文件
            allChangedFiles.addAll(status.getRemoved());
            allChangedFiles.addAll(status.getMissing());
            
            // 过滤只保留 Java 文件
            List<String> javaFiles = allChangedFiles.stream()
                    .filter(f -> f.endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
            
            StringBuilder result = new StringBuilder();
            result.append("Uncommitted Changes Summary:\n");
            result.append("  Total changed files: ").append(allChangedFiles.size()).append("\n");
            result.append("  Java files changed: ").append(javaFiles.size()).append("\n");
            
            if (!javaFiles.isEmpty()) {
                result.append("\nChanged Java Files:\n");
                for (String file : javaFiles) {
                    String changeType = getChangeType(status, file);
                    result.append(String.format("  [%s] %s\n", changeType, file));
                }
            }
            
            String resultStr = result.toString();
            log.info("Tool Output - getUncommittedChanges: {} Java files changed", javaFiles.size());
            return resultStr;
            
        } catch (Exception e) {
            throw new AgentToolException(EXTERNAL_TOOL_ERROR, 
                    "Failed to get uncommitted changes", 
                    "Repository: " + repoPath, 
                    "Ensure the path is a valid Git repository", e);
        }
    }

    /**
     * 仅获取暂存区的更改（已 git add 但未 commit）
     */
    @Tool("Get only staged changes (files added with 'git add' but not yet committed).")
    public String getStagedChanges(
            @P("Path to the Git repository root") String repoPath) throws IOException {
        log.info("Tool Input - getStagedChanges: repoPath={}", repoPath);
        
        try (Git git = Git.open(new File(repoPath))) {
            Status status = git.status().call();
            
            Set<String> stagedFiles = new HashSet<>();
            stagedFiles.addAll(status.getAdded());
            stagedFiles.addAll(status.getChanged());
            stagedFiles.addAll(status.getRemoved());
            
            List<String> javaFiles = stagedFiles.stream()
                    .filter(f -> f.endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
            
            StringBuilder result = new StringBuilder();
            result.append("Staged Changes Summary:\n");
            result.append("  Total staged files: ").append(stagedFiles.size()).append("\n");
            result.append("  Java files staged: ").append(javaFiles.size()).append("\n");
            
            if (!javaFiles.isEmpty()) {
                result.append("\nStaged Java Files:\n");
                for (String file : javaFiles) {
                    result.append("  ").append(file).append("\n");
                }
            }
            
            String resultStr = result.toString();
            log.info("Tool Output - getStagedChanges: {} Java files staged", javaFiles.size());
            return resultStr;
            
        } catch (Exception e) {
            throw new AgentToolException(EXTERNAL_TOOL_ERROR, 
                    "Failed to get staged changes", 
                    "Repository: " + repoPath, 
                    "Ensure the path is a valid Git repository", e);
        }
    }

    /**
     * 比较任意两个 Git 引用（分支、标签、commit hash）
     * 这个方法不预设任何分支名，完全由用户指定
     */
    @Tool("Compare changes between any two Git references (branches, tags, or commit hashes). Returns changed Java files between fromRef and toRef.")
    public String getChangesBetweenRefs(
            @P("Path to the Git repository root") String repoPath,
            @P("Base reference (branch name, tag, or commit hash). Use 'HEAD~N' for N commits ago.") String fromRef,
            @P("Target reference to compare with. Use 'HEAD' for current state, or any branch/tag/commit.") String toRef) throws IOException {
        log.info("Tool Input - getChangesBetweenRefs: repoPath={}, fromRef={}, toRef={}", repoPath, fromRef, toRef);
        
        try (Git git = Git.open(new File(repoPath))) {
            Repository repository = git.getRepository();
            
            // 解析引用
            ObjectId fromCommit = repository.resolve(fromRef);
            ObjectId toCommit = repository.resolve(toRef);
            
            if (fromCommit == null) {
                return "ERROR: Cannot resolve reference '" + fromRef + "'. Check if the branch/tag/commit exists.";
            }
            if (toCommit == null) {
                return "ERROR: Cannot resolve reference '" + toRef + "'. Check if the branch/tag/commit exists.";
            }
            
            // 获取差异
            List<DiffEntry> diffs = getDiffsBetweenCommits(repository, fromCommit, toCommit);
            
            // 过滤 Java 文件
            List<ChangedFile> changedFiles = diffs.stream()
                    .filter(d -> {
                        String path = d.getNewPath().equals("/dev/null") ? d.getOldPath() : d.getNewPath();
                        return path.endsWith(".java");
                    })
                    .map(d -> ChangedFile.builder()
                            .path(d.getNewPath().equals("/dev/null") ? d.getOldPath() : d.getNewPath())
                            .changeType(d.getChangeType().name())
                            .build())
                    .collect(Collectors.toList());
            
            StringBuilder result = new StringBuilder();
            result.append(String.format("Changes between '%s' and '%s':\n", fromRef, toRef));
            result.append("  Total diff entries: ").append(diffs.size()).append("\n");
            result.append("  Java files changed: ").append(changedFiles.size()).append("\n");
            
            if (!changedFiles.isEmpty()) {
                result.append("\nChanged Java Files:\n");
                for (ChangedFile file : changedFiles) {
                    result.append(String.format("  [%s] %s\n", file.getChangeType(), file.getPath()));
                }
            }
            
            String resultStr = result.toString();
            log.info("Tool Output - getChangesBetweenRefs: {} Java files changed", changedFiles.size());
            return resultStr;
            
        } catch (Exception e) {
            throw new AgentToolException(EXTERNAL_TOOL_ERROR, 
                    "Failed to compare refs", 
                    String.format("Repository: %s, fromRef: %s, toRef: %s", repoPath, fromRef, toRef), 
                    "Ensure both references exist in the repository", e);
        }
    }

    /**
     * 获取当前仓库的分支列表，帮助用户选择要比较的分支
     */
    @Tool("List all branches in the repository. Useful for discovering available branches before comparing.")
    public String listBranches(
            @P("Path to the Git repository root") String repoPath) throws IOException {
        log.info("Tool Input - listBranches: repoPath={}", repoPath);
        
        try (Git git = Git.open(new File(repoPath))) {
            List<Ref> branches = git.branchList().call();
            
            StringBuilder result = new StringBuilder();
            result.append("Available Branches:\n");
            
            String currentBranch = git.getRepository().getBranch();
            result.append("  Current: ").append(currentBranch).append("\n\n");
            
            result.append("Local Branches:\n");
            for (Ref branch : branches) {
                String branchName = branch.getName().replace("refs/heads/", "");
                String marker = branchName.equals(currentBranch) ? " *" : "";
                result.append("  ").append(branchName).append(marker).append("\n");
            }
            
            String resultStr = result.toString();
            log.info("Tool Output - listBranches: {} branches found", branches.size());
            return resultStr;
            
        } catch (Exception e) {
            throw new AgentToolException(EXTERNAL_TOOL_ERROR, 
                    "Failed to list branches", 
                    "Repository: " + repoPath, 
                    "Ensure the path is a valid Git repository", e);
        }
    }

    /**
     * 分析变更的 Java 类，返回需要生成/更新测试的类列表
     * 只返回 src/main/java 下的源码类，排除测试类
     */
    @Tool("Analyze changed Java source files and return classes that may need test updates. Filters out test classes.")
    public List<ChangedJavaClass> analyzeChangedSourceClasses(
            @P("Path to the Git repository root") String repoPath) throws IOException {
        log.info("Tool Input - analyzeChangedSourceClasses: repoPath={}", repoPath);
        
        try (Git git = Git.open(new File(repoPath))) {
            Status status = git.status().call();
            
            Set<String> allChangedFiles = new HashSet<>();
            allChangedFiles.addAll(status.getModified());
            allChangedFiles.addAll(status.getChanged());
            allChangedFiles.addAll(status.getUntracked());
            allChangedFiles.addAll(status.getAdded());
            
            List<ChangedJavaClass> changedClasses = allChangedFiles.stream()
                    .filter(f -> f.endsWith(".java"))
                    .filter(f -> f.contains("src/main/java") || f.contains("src\\main\\java"))
                    .filter(f -> !f.contains("src/test/java") && !f.contains("src\\test\\java"))
                    .map(f -> {
                        String className = extractClassName(f);
                        return ChangedJavaClass.builder()
                                .filePath(f)
                                .className(className)
                                .changeType(getChangeType(status, f))
                                .needsTestUpdate(true)
                                .build();
                    })
                    .collect(Collectors.toList());
            
            log.info("Tool Output - analyzeChangedSourceClasses: {} source classes changed", changedClasses.size());
            return changedClasses;
            
        } catch (Exception e) {
            throw new AgentToolException(EXTERNAL_TOOL_ERROR, 
                    "Failed to analyze changed source classes", 
                    "Repository: " + repoPath, 
                    "Ensure the path is a valid Git repository", e);
        }
    }

    /**
     * 获取变更文件的详细 diff 内容
     */
    @Tool("Get the detailed diff content for a specific file compared to HEAD.")
    public String getFileDiff(
            @P("Path to the Git repository root") String repoPath,
            @P("Path to the file relative to repository root") String filePath) throws IOException {
        log.info("Tool Input - getFileDiff: repoPath={}, filePath={}", repoPath, filePath);
        
        try (Git git = Git.open(new File(repoPath))) {
            Repository repository = git.getRepository();
            
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repository);
                formatter.setDiffComparator(RawTextComparator.DEFAULT);
                formatter.setDetectRenames(true);
                
                // 获取 HEAD 的 tree
                ObjectId head = repository.resolve("HEAD^{tree}");
                AbstractTreeIterator headTree = prepareTreeParser(repository, head);
                
                // 获取工作区的 tree
                FileTreeIterator workingTree = new FileTreeIterator(repository);
                
                List<DiffEntry> diffs = formatter.scan(headTree, workingTree);
                
                for (DiffEntry diff : diffs) {
                    String diffPath = diff.getNewPath().equals("/dev/null") ? diff.getOldPath() : diff.getNewPath();
                    if (diffPath.equals(filePath) || diffPath.replace("/", "\\").equals(filePath)) {
                        formatter.format(diff);
                        break;
                    }
                }
            }
            
            String diffContent = out.toString();
            if (diffContent.isEmpty()) {
                diffContent = "No diff found for file: " + filePath + ". The file may not have changes or may not exist.";
            }
            
            log.info("Tool Output - getFileDiff: diff length={}", diffContent.length());
            return diffContent;
            
        } catch (Exception e) {
            throw new AgentToolException(EXTERNAL_TOOL_ERROR, 
                    "Failed to get file diff", 
                    String.format("Repository: %s, File: %s", repoPath, filePath), 
                    "Ensure the file path is correct and the file has changes", e);
        }
    }

    /**
     * 获取仓库状态摘要
     */
    @Tool("Get a summary of the repository status including current branch and change counts.")
    public String getRepositoryStatus(
            @P("Path to the Git repository root") String repoPath) throws IOException {
        log.info("Tool Input - getRepositoryStatus: repoPath={}", repoPath);
        
        try (Git git = Git.open(new File(repoPath))) {
            Repository repository = git.getRepository();
            Status status = git.status().call();
            
            StringBuilder result = new StringBuilder();
            result.append("Repository Status:\n");
            result.append("  Path: ").append(repository.getDirectory().getParent()).append("\n");
            result.append("  Current Branch: ").append(repository.getBranch()).append("\n");
            result.append("  Is Clean: ").append(status.isClean()).append("\n\n");
            
            result.append("Change Summary:\n");
            result.append("  Added (staged): ").append(status.getAdded().size()).append("\n");
            result.append("  Changed (staged): ").append(status.getChanged().size()).append("\n");
            result.append("  Removed (staged): ").append(status.getRemoved().size()).append("\n");
            result.append("  Modified (unstaged): ").append(status.getModified().size()).append("\n");
            result.append("  Untracked: ").append(status.getUntracked().size()).append("\n");
            result.append("  Missing: ").append(status.getMissing().size()).append("\n");
            result.append("  Conflicting: ").append(status.getConflicting().size()).append("\n");
            
            String resultStr = result.toString();
            log.info("Tool Output - getRepositoryStatus: isClean={}", status.isClean());
            return resultStr;
            
        } catch (Exception e) {
            throw new AgentToolException(EXTERNAL_TOOL_ERROR, 
                    "Failed to get repository status", 
                    "Repository: " + repoPath, 
                    "Ensure the path is a valid Git repository", e);
        }
    }

    // ==================== 辅助方法 ====================

    private String getChangeType(Status status, String file) {
        if (status.getAdded().contains(file)) return "ADDED";
        if (status.getChanged().contains(file)) return "MODIFIED";
        if (status.getModified().contains(file)) return "MODIFIED";
        if (status.getRemoved().contains(file)) return "DELETED";
        if (status.getMissing().contains(file)) return "MISSING";
        if (status.getUntracked().contains(file)) return "UNTRACKED";
        return "UNKNOWN";
    }

    private String extractClassName(String filePath) {
        // 从 src/main/java/com/example/MyClass.java 提取 com.example.MyClass
        String normalized = filePath.replace("\\", "/");
        int srcMainJava = normalized.indexOf("src/main/java/");
        if (srcMainJava >= 0) {
            String classPath = normalized.substring(srcMainJava + "src/main/java/".length());
            return classPath.replace("/", ".").replace(".java", "");
        }
        // 尝试简单提取
        Path path = Paths.get(filePath);
        return path.getFileName().toString().replace(".java", "");
    }

    private List<DiffEntry> getDiffsBetweenCommits(Repository repository, ObjectId fromCommit, ObjectId toCommit) throws IOException {
        try (DiffFormatter formatter = new DiffFormatter(new ByteArrayOutputStream())) {
            formatter.setRepository(repository);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);
            
            AbstractTreeIterator fromTree = prepareTreeParser(repository, fromCommit);
            AbstractTreeIterator toTree = prepareTreeParser(repository, toCommit);
            
            return formatter.scan(fromTree, toTree);
        }
    }

    private AbstractTreeIterator prepareTreeParser(Repository repository, ObjectId objectId) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(objectId);
            ObjectId treeId = commit.getTree().getId();
            
            try (ObjectReader reader = repository.newObjectReader()) {
                CanonicalTreeParser treeParser = new CanonicalTreeParser();
                treeParser.reset(reader, treeId);
                return treeParser;
            }
        }
    }

    // ==================== 数据类 ====================

    @Data
    @Builder
    public static class ChangedFile {
        private String path;
        private String changeType;
    }

    @Data
    @Builder
    public static class ChangedJavaClass {
        private String filePath;
        private String className;
        private String changeType;
        private boolean needsTestUpdate;
    }
}
