package com.codelogickeep.agent.ut.framework.pipeline;

/**
 * 修复提示词构建器 - 为不同类型的错误生成修复提示词
 */
public class FixPromptBuilder {
    
    /**
     * 构建生成测试代码的提示词
     */
    public static String buildGenerateTestPrompt(String targetFile, String methodName, 
            String testFilePath, double currentCoverage) {
        return String.format("""
                ## 生成测试代码
                
                **目标文件**: %s
                **目标方法**: `%s`
                **测试文件**: %s
                **当前覆盖率**: %.1f%%
                
                请为方法 `%s` 生成单元测试：
                
                1. 使用 `readFile("%s")` 读取当前测试文件（如果存在）
                2. 分析方法的代码逻辑，识别需要测试的路径
                3. 生成测试代码，覆盖：
                   - 正常路径
                   - 边界条件
                   - 异常处理
                4. 使用 `writeFileFromLine` 追加测试到文件末尾
                
                ⚠️ **重要**：只需要写代码！验证流程（编译、测试、覆盖率）会自动执行！
                ⚠️ **不要**调用 checkSyntax、compileProject、executeTest 等工具！
                
                完成写代码后，回复 "代码已写入" 即可。
                """, 
                targetFile, methodName, testFilePath, currentCoverage,
                methodName, testFilePath);
    }
    
    /**
     * 构建修复语法错误的提示词
     */
    public static String buildSyntaxFixPrompt(String testFilePath, String errorMessage) {
        return String.format("""
                ## 修复语法错误
                
                **测试文件**: %s
                
                **错误信息**:
                ```
                %s
                ```
                
                请修复上述语法错误：
                
                1. 使用 `readFile("%s")` 读取测试文件
                2. 分析错误信息，定位问题（常见问题：缺少分号、括号不匹配、import 缺失）
                3. 使用 `searchReplace` 或 `writeFile` 修复代码
                
                ⚠️ 修复后不需要调用 checkSyntax，系统会自动验证！
                
                完成修复后，回复 "已修复" 即可。
                """, 
                testFilePath, truncateError(errorMessage), testFilePath);
    }
    
    /**
     * 构建修复 LSP 错误的提示词
     */
    public static String buildLspFixPrompt(String testFilePath, String errorMessage) {
        return String.format("""
                ## 修复 LSP 语义错误
                
                **测试文件**: %s
                
                **LSP 错误信息**:
                ```
                %s
                ```
                
                请修复上述 LSP 错误：
                
                1. 使用 `readFile("%s")` 读取测试文件
                2. 分析错误信息：
                   - "cannot be resolved to a type" → 添加缺少的 import
                   - "Duplicate annotation" → 删除重复的注解
                   - "method undefined" → 检查方法名和签名
                   - "Type mismatch" → 修复类型转换
                3. 使用 `searchReplace` 精确修复问题行
                
                ⚠️ 修复后系统会自动重新检查！
                
                完成修复后，回复 "已修复" 即可。
                """, 
                testFilePath, truncateError(errorMessage), testFilePath);
    }
    
    /**
     * 构建修复编译错误的提示词
     */
    public static String buildCompileFixPrompt(String testFilePath, String errorMessage) {
        return String.format("""
                ## 修复编译错误
                
                **测试文件**: %s
                
                **编译错误**:
                ```
                %s
                ```
                
                请修复上述编译错误：
                
                1. 使用 `readFile("%s")` 读取测试文件
                2. 如果需要，也读取源文件了解类的 API
                3. 分析错误信息，常见问题：
                   - 缺少 import 语句
                   - 类型不匹配
                   - 方法签名错误
                   - 依赖缺失
                4. 修复代码
                
                ⚠️ 修复后系统会自动重新编译！
                
                完成修复后，回复 "已修复" 即可。
                """, 
                testFilePath, truncateError(errorMessage), testFilePath);
    }
    
    /**
     * 构建修复测试失败的提示词
     */
    public static String buildTestFixPrompt(String testFilePath, String testClassName, String errorMessage) {
        return String.format("""
                ## 修复测试失败
                
                **测试文件**: %s
                **测试类**: %s
                
                **测试失败信息**:
                ```
                %s
                ```
                
                请修复测试失败：
                
                1. 使用 `readFile("%s")` 读取测试文件
                2. 分析失败原因：
                   - 断言失败 → 检查预期值和实际值
                   - Mock 配置错误 → 检查 when/thenReturn 设置
                   - NullPointerException → 检查 Mock 对象是否正确注入
                   - 异常未捕获 → 添加 @Test(expected=...) 或 assertThrows
                3. 修复测试代码
                
                ⚠️ 修复后系统会自动重新执行测试！
                
                完成修复后，回复 "已修复" 即可。
                """, 
                testFilePath, testClassName, truncateError(errorMessage), testFilePath);
    }
    
    /**
     * 构建继续生成测试的提示词（覆盖率不足时）
     */
    public static String buildMoreTestsPrompt(String targetFile, String methodName, 
            String testFilePath, double currentCoverage, int threshold) {
        return String.format("""
                ## 覆盖率不足，需要更多测试
                
                **目标方法**: `%s`
                **当前覆盖率**: %.1f%% (目标: %d%%)
                **差距**: %.1f%%
                
                请生成更多测试用例：
                
                1. 使用 `readFile("%s")` 读取当前测试文件，了解已有测试
                2. 使用 `readFile("%s")` 读取源代码，分析未覆盖的路径
                3. 生成针对未覆盖路径的测试，重点关注：
                   - 边界条件（空值、最大/最小值）
                   - 异常处理路径
                   - 分支条件（if/else、switch）
                4. 使用 `writeFileFromLine` 追加新测试
                
                ⚠️ 只需要写代码！验证流程会自动执行！
                
                完成后，回复 "代码已写入" 即可。
                """, 
                methodName, currentCoverage, threshold, threshold - currentCoverage,
                testFilePath, targetFile);
    }
    
    /**
     * 截断过长的错误信息
     */
    private static String truncateError(String error) {
        if (error == null) {
            return "无详细信息";
        }
        // 限制错误信息长度，避免提示词过长
        if (error.length() > 2000) {
            return error.substring(0, 2000) + "\n... (错误信息已截断)";
        }
        return error;
    }
}
