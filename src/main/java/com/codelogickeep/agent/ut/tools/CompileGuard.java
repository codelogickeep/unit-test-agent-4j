package com.codelogickeep.agent.ut.tools;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编译守卫 - 在工具层面强制执行"先语法检查通过再编译"的流程
 * 
 * 工作原理：
 * 1. 文件写入后，标记该文件需要语法检查
 * 2. 语法检查通过后，标记该文件可以编译
 * 3. 编译前检查是否有文件未通过语法检查
 * 
 * 这是一个单例类，所有工具共享同一个实例。
 */
@Slf4j
public class CompileGuard {
    
    private static final CompileGuard INSTANCE = new CompileGuard();
    
    // 文件状态：true = 语法检查通过, false = 需要语法检查
    private final Map<String, FileStatus> fileStatusMap = new ConcurrentHashMap<>();
    
    // 是否启用守卫（可配置）
    private boolean enabled = true;
    
    private CompileGuard() {}
    
    public static CompileGuard getInstance() {
        return INSTANCE;
    }
    
    /**
     * 启用或禁用编译守卫
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("CompileGuard enabled: {}", enabled);
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * 标记文件已被修改，需要语法检查
     * 在 FileWriterTool 写入文件后调用
     */
    public void markFileModified(String filePath) {
        if (!enabled) return;
        
        String normalizedPath = normalizePath(filePath);
        fileStatusMap.put(normalizedPath, new FileStatus(false, Instant.now(), null));
        log.debug("File marked as modified (needs syntax check): {}", normalizedPath);
    }
    
    /**
     * 标记文件语法检查通过
     * 在 SyntaxCheckerTool/LspSyntaxCheckerTool 检查通过后调用
     */
    public void markSyntaxPassed(String filePath) {
        if (!enabled) return;
        
        String normalizedPath = normalizePath(filePath);
        fileStatusMap.put(normalizedPath, new FileStatus(true, Instant.now(), null));
        log.debug("File marked as syntax passed: {}", normalizedPath);
    }
    
    /**
     * 标记文件语法检查失败
     * 在 SyntaxCheckerTool/LspSyntaxCheckerTool 检查失败后调用
     */
    public void markSyntaxFailed(String filePath, String errorMessage) {
        if (!enabled) return;
        
        String normalizedPath = normalizePath(filePath);
        fileStatusMap.put(normalizedPath, new FileStatus(false, Instant.now(), errorMessage));
        log.debug("File marked as syntax failed: {}", normalizedPath);
    }
    
    /**
     * 检查是否可以编译
     * 返回 null 表示可以编译，否则返回阻止编译的原因
     */
    public CompileCheckResult canCompile() {
        if (!enabled) {
            return CompileCheckResult.ok();
        }
        
        // 收集所有未通过语法检查的文件
        StringBuilder failedFiles = new StringBuilder();
        int failedCount = 0;
        
        for (Map.Entry<String, FileStatus> entry : fileStatusMap.entrySet()) {
            FileStatus status = entry.getValue();
            if (!status.syntaxPassed) {
                failedCount++;
                failedFiles.append("\n  - ").append(entry.getKey());
                if (status.lastError != null) {
                    // 只显示错误的前100个字符
                    String shortError = status.lastError.length() > 100 
                            ? status.lastError.substring(0, 100) + "..." 
                            : status.lastError;
                    failedFiles.append(": ").append(shortError);
                }
            }
        }
        
        if (failedCount > 0) {
            String message = String.format(
                "COMPILE_BLOCKED: %d file(s) have not passed syntax check.%s\n\n" +
                "⚠️ REQUIRED ACTION:\n" +
                "1. Use checkSyntax(filePath) or checkSyntaxWithLsp(filePath) to check the file\n" +
                "2. If errors are found, fix them using searchReplace() or writeFile()\n" +
                "3. Re-run syntax check until it passes\n" +
                "4. Then call compileProject() again",
                failedCount, failedFiles.toString()
            );
            return CompileCheckResult.blocked(message);
        }
        
        return CompileCheckResult.ok();
    }
    
    /**
     * 清除文件状态（用于测试或重置）
     */
    public void clearStatus(String filePath) {
        String normalizedPath = normalizePath(filePath);
        fileStatusMap.remove(normalizedPath);
    }
    
    /**
     * 清除所有状态
     */
    public void clearAllStatus() {
        fileStatusMap.clear();
        log.debug("All file status cleared");
    }
    
    /**
     * 获取当前状态摘要
     */
    public String getStatusSummary() {
        if (!enabled) {
            return "CompileGuard is DISABLED";
        }
        
        long passedCount = fileStatusMap.values().stream().filter(s -> s.syntaxPassed).count();
        long failedCount = fileStatusMap.size() - passedCount;
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("CompileGuard Status: %d passed, %d pending/failed\n", passedCount, failedCount));
        
        if (failedCount > 0) {
            sb.append("Files needing syntax check:\n");
            fileStatusMap.entrySet().stream()
                    .filter(e -> !e.getValue().syntaxPassed)
                    .forEach(e -> sb.append("  - ").append(e.getKey()).append("\n"));
        }
        
        return sb.toString();
    }
    
    private String normalizePath(String filePath) {
        try {
            return Path.of(filePath).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return filePath;
        }
    }
    
    // 文件状态记录
    private record FileStatus(boolean syntaxPassed, Instant lastChecked, String lastError) {}
    
    // 编译检查结果
    public record CompileCheckResult(boolean canCompile, String blockReason) {
        public static CompileCheckResult ok() {
            return new CompileCheckResult(true, null);
        }
        
        public static CompileCheckResult blocked(String reason) {
            return new CompileCheckResult(false, reason);
        }
    }
}
