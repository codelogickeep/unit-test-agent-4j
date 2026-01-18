package com.codelogickeep.agent.ut.framework.pipeline;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动化验证管道 - 负责执行固定的验证流程
 * 
 * 流程: checkSyntax → checkSyntaxWithLsp → compileProject → executeTest → getCoverage
 * 
 * 每个步骤失败时返回错误信息，由调用方决定是否调用 LLM 修复
 */
public class VerificationPipeline {
    private static final Logger log = LoggerFactory.getLogger(VerificationPipeline.class);
    
    private final ToolRegistry toolRegistry;
    private final AppConfig config;
    private final boolean lspEnabled;
    
    public VerificationPipeline(ToolRegistry toolRegistry, AppConfig config) {
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.lspEnabled = config.getWorkflow() != null && config.getWorkflow().isUseLsp();
    }
    
    /**
     * 执行验证管道
     * 
     * @param testFilePath 测试文件路径（相对于项目根目录）
     * @param testClassName 测试类全限定名
     * @param targetClassName 目标类全限定名
     * @param methodName 目标方法名
     * @param modulePath 模块路径
     * @return 验证结果
     */
    public VerificationResult execute(
            String testFilePath,
            String testClassName,
            String targetClassName,
            String methodName,
            String modulePath) {
        
        log.info("🔄 Starting verification pipeline for method: {}", methodName);
        System.out.println("\n" + "─".repeat(50));
        System.out.println("🔄 自动验证管道开始");
        System.out.println("─".repeat(50));
        
        // Step 1: 语法检查
        System.out.println("\n📝 Step 1/5: 语法检查...");
        VerificationResult syntaxResult = runSyntaxCheck(testFilePath);
        if (!syntaxResult.isSuccess()) {
            log.warn("❌ Syntax check failed: {}", syntaxResult.getErrorMessage());
            System.out.println("❌ 语法检查失败");
            return syntaxResult;
        }
        System.out.println("✅ 语法检查通过");
        
        // Step 2: LSP 语法检查（如果启用）
        if (lspEnabled) {
            System.out.println("\n🔍 Step 2/5: LSP语义检查...");
            VerificationResult lspResult = runLspCheck(testFilePath);
            if (!lspResult.isSuccess()) {
                log.warn("❌ LSP check failed: {}", lspResult.getErrorMessage());
                System.out.println("❌ LSP检查失败");
                return lspResult;
            }
            System.out.println("✅ LSP检查通过");
        } else {
            System.out.println("\n⏭️ Step 2/5: LSP检查已跳过（未启用）");
        }
        
        // Step 3: 编译
        System.out.println("\n🔨 Step 3/5: 编译项目...");
        VerificationResult compileResult = runCompile();
        if (!compileResult.isSuccess()) {
            log.warn("❌ Compilation failed: {}", compileResult.getErrorMessage());
            System.out.println("❌ 编译失败");
            return compileResult;
        }
        System.out.println("✅ 编译成功");
        
        // Step 4: 执行测试
        System.out.println("\n🧪 Step 4/5: 执行测试...");
        VerificationResult testResult = runTest(testClassName);
        if (!testResult.isSuccess()) {
            log.warn("❌ Test execution failed: {}", testResult.getErrorMessage());
            System.out.println("❌ 测试失败");
            return testResult;
        }
        System.out.println("✅ 测试通过");
        
        // Step 5: 获取覆盖率
        System.out.println("\n📊 Step 5/5: 计算覆盖率...");
        double coverage = getCoverage(modulePath, targetClassName, methodName);
        int threshold = config.getWorkflow() != null ? config.getWorkflow().getCoverageThreshold() : 80;
        boolean thresholdMet = coverage >= threshold;
        
        System.out.printf("📊 方法 %s 覆盖率: %.1f%% (目标: %d%%)%n", methodName, coverage, threshold);
        if (thresholdMet) {
            System.out.println("✅ 覆盖率达标");
        } else {
            System.out.println("⚠️ 覆盖率未达标，需要更多测试");
        }
        
        System.out.println("─".repeat(50));
        log.info("✅ Verification pipeline completed. Coverage: {}%, ThresholdMet: {}", 
                String.format("%.1f", coverage), thresholdMet);
        
        return VerificationResult.success(coverage, thresholdMet);
    }
    
    /**
     * 执行语法检查
     */
    private VerificationResult runSyntaxCheck(String testFilePath) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("filePath", testFilePath);
            
            log.info("📝 checkSyntax 输入: filePath={}", testFilePath);
            String result = toolRegistry.invoke("checkSyntax", args);
            log.info("📝 checkSyntax 输出: {}", truncateForLog(result));
            
            if (result == null) {
                return VerificationResult.failure(VerificationStep.SYNTAX_CHECK, "工具返回 null");
            }
            
            // 先检查是否有错误（case-insensitive）
            String resultLower = result.toLowerCase();
            if (resultLower.startsWith("error") || resultLower.contains("missing required parameter")) {
                return VerificationResult.failure(VerificationStep.SYNTAX_CHECK, "工具调用错误", result);
            }
            
            // 解析结果
            if (result.contains("VALID") || result.contains("LSP_OK") || result.contains("No errors") ||
                result.contains("SYNTAX_OK")) {
                return VerificationResult.success(0, false);
            } else if (result.contains("ERROR") || result.contains("LSP_ERRORS") || result.contains("INVALID")) {
                return VerificationResult.failure(VerificationStep.SYNTAX_CHECK, "语法错误", result);
            }
            
            // 默认认为通过
            return VerificationResult.success(0, false);
        } catch (Exception e) {
            log.error("Syntax check exception", e);
            return VerificationResult.failure(VerificationStep.SYNTAX_CHECK, e.getMessage());
        }
    }
    
    /**
     * 执行 LSP 语法检查
     */
    private VerificationResult runLspCheck(String testFilePath) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("filePath", testFilePath);
            
            log.info("🔍 checkSyntaxWithLsp 输入: filePath={}", testFilePath);
            String result = toolRegistry.invoke("checkSyntaxWithLsp", args);
            log.info("🔍 checkSyntaxWithLsp 输出: {}", truncateForLog(result));
            
            if (result == null) {
                return VerificationResult.failure(VerificationStep.LSP_CHECK, "工具返回 null");
            }
            
            // 先检查是否有错误（case-insensitive）
            String resultLower = result.toLowerCase();
            if (resultLower.startsWith("error") || resultLower.contains("missing required parameter")) {
                return VerificationResult.failure(VerificationStep.LSP_CHECK, "工具调用错误", result);
            }
            
            // 解析结果
            if (result.contains("LSP_OK") || result.contains("No errors")) {
                return VerificationResult.success(0, false);
            } else if (result.contains("LSP_ERRORS") || result.contains("ERROR")) {
                return VerificationResult.failure(VerificationStep.LSP_CHECK, "LSP检查发现错误", result);
            } else if (result.contains("LSP_WARNINGS")) {
                // 警告不阻断流程
                log.warn("LSP check has warnings: {}", result);
                return VerificationResult.success(0, false);
            }
            
            return VerificationResult.success(0, false);
        } catch (Exception e) {
            log.error("LSP check exception", e);
            return VerificationResult.failure(VerificationStep.LSP_CHECK, e.getMessage());
        }
    }
    
    /**
     * 执行编译
     */
    private VerificationResult runCompile() {
        try {
            Map<String, Object> args = new HashMap<>();
            
            log.info("🔨 compileProject 输入: (无参数)");
            String result = toolRegistry.invoke("compileProject", args);
            log.info("🔨 compileProject 输出: {}", truncateForLog(result));
            
            if (result == null) {
                return VerificationResult.failure(VerificationStep.COMPILE, "工具返回 null");
            }
            
            // 先检查是否有工具错误（case-insensitive）
            String resultLower = result.toLowerCase();
            if (resultLower.startsWith("error") || resultLower.contains("missing required parameter")) {
                return VerificationResult.failure(VerificationStep.COMPILE, "工具调用错误", result);
            }
            
            // 检查 CompileGuard 阻止编译
            if (result.contains("COMPILE_BLOCKED")) {
                return VerificationResult.failure(VerificationStep.COMPILE, "编译被阻止（语法检查未通过）", result);
            }
            
            // 解析结果
            if (result.contains("exitCode=0") || result.contains("\"exitCode\":0") || 
                result.contains("BUILD SUCCESS") || result.contains("Compilation successful")) {
                return VerificationResult.success(0, false);
            } else if (result.contains("exitCode=1") || result.contains("\"exitCode\":1") ||
                       result.contains("BUILD FAILURE") || result.contains("COMPILATION ERROR")) {
                return VerificationResult.failure(VerificationStep.COMPILE, "编译失败", result);
            }
            
            // 如果没有明显的失败标记，假设成功
            return VerificationResult.success(0, false);
        } catch (Exception e) {
            log.error("Compile exception", e);
            return VerificationResult.failure(VerificationStep.COMPILE, e.getMessage());
        }
    }
    
    /**
     * 执行测试
     */
    private VerificationResult runTest(String testClassName) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("testClassName", testClassName);
            
            log.info("🧪 executeTest 输入: testClassName={}", testClassName);
            String result = toolRegistry.invoke("executeTest", args);
            log.info("🧪 executeTest 输出: {}", truncateForLog(result));
            log.debug("executeTest result length: {}", result != null ? result.length() : 0);
            
            if (result == null) {
                return VerificationResult.failure(VerificationStep.TEST, "工具返回 null");
            }
            
            // 先检查是否有工具错误（case-insensitive）
            String resultLower = result.toLowerCase();
            if (resultLower.startsWith("error") || resultLower.contains("missing required parameter")) {
                return VerificationResult.failure(VerificationStep.TEST, "工具调用错误", result);
            }
            
            // 解析测试结果
            if (result.contains("exitCode=0") || result.contains("\"exitCode\":0") ||
                result.contains("BUILD SUCCESS")) {
                return VerificationResult.success(0, false);
            }
            
            // 更精确检查测试通过
            if (result.contains("Tests run:") && result.contains("Failures: 0") && result.contains("Errors: 0")) {
                return VerificationResult.success(0, false);
            }
            
            if (result.contains("exitCode=1") || result.contains("\"exitCode\":1") ||
                result.contains("BUILD FAILURE") || result.contains("FAILURE!") ||
                result.contains("Failures:") && !result.contains("Failures: 0")) {
                return VerificationResult.failure(VerificationStep.TEST, "测试失败", result);
            }
            
            // 检查是否有测试失败
            Pattern failurePattern = Pattern.compile("Failures:\\s*(\\d+)");
            Matcher failureMatcher = failurePattern.matcher(result);
            if (failureMatcher.find()) {
                int failures = Integer.parseInt(failureMatcher.group(1));
                if (failures > 0) {
                    return VerificationResult.failure(VerificationStep.TEST, 
                            String.format("%d 个测试失败", failures), result);
                }
            }
            
            Pattern errorPattern = Pattern.compile("Errors:\\s*(\\d+)");
            Matcher errorMatcher = errorPattern.matcher(result);
            if (errorMatcher.find()) {
                int errors = Integer.parseInt(errorMatcher.group(1));
                if (errors > 0) {
                    return VerificationResult.failure(VerificationStep.TEST, 
                            String.format("%d 个测试错误", errors), result);
                }
            }
            
            // 默认成功
            return VerificationResult.success(0, false);
        } catch (Exception e) {
            log.error("Test execution exception", e);
            return VerificationResult.failure(VerificationStep.TEST, e.getMessage());
        }
    }
    
    /**
     * 获取方法覆盖率
     */
    private double getCoverage(String modulePath, String className, String methodName) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("modulePath", modulePath);
            args.put("className", className);
            args.put("methodName", methodName);
            
            String result = toolRegistry.invoke("getSingleMethodCoverage", args);
            log.debug("getSingleMethodCoverage result: {}", result);
            
            if (result == null || result.toLowerCase().startsWith("error")) {
                log.warn("Failed to get coverage: {}", result);
                return 0;
            }
            
            // 解析覆盖率，格式通常是: "methodName line=XX.X%"
            Pattern pattern = Pattern.compile("line[=:]\\s*([\\d.]+)%");
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }
            
            // 尝试其他格式
            Pattern altPattern = Pattern.compile("([\\d.]+)%");
            Matcher altMatcher = altPattern.matcher(result);
            if (altMatcher.find()) {
                return Double.parseDouble(altMatcher.group(1));
            }
            
            return 0;
        } catch (Exception e) {
            log.error("Coverage check exception", e);
            return 0;
        }
    }
    
    /**
     * 单独执行语法检查（用于修复后重试）
     */
    public VerificationResult checkSyntaxOnly(String testFilePath) {
        VerificationResult result = runSyntaxCheck(testFilePath);
        if (result.isSuccess() && lspEnabled) {
            return runLspCheck(testFilePath);
        }
        return result;
    }
    
    /**
     * 单独执行编译（用于修复后重试）
     */
    public VerificationResult compileOnly() {
        return runCompile();
    }
    
    /**
     * 单独执行测试（用于修复后重试）
     */
    public VerificationResult testOnly(String testClassName) {
        return runTest(testClassName);
    }
    
    /**
     * 截断日志输出，避免过长
     */
    private String truncateForLog(String text) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= 200) {
            return text.replace("\n", " ");
        }
        return text.substring(0, 200).replace("\n", " ") + "... (truncated, total: " + text.length() + " chars)";
    }
}
