package com.codelogickeep.agent.ut.framework.pipeline;

/**
 * 验证管道中的步骤枚举
 */
public enum VerificationStep {
    /** 语法检查 */
    SYNTAX_CHECK("checkSyntax", "语法检查"),
    
    /** LSP 语法检查 */
    LSP_CHECK("checkSyntaxWithLsp", "LSP语义检查"),
    
    /** 编译项目 */
    COMPILE("compileProject", "编译项目"),
    
    /** 执行测试 */
    TEST("executeTest", "执行测试"),
    
    /** 覆盖率检查 */
    COVERAGE("getSingleMethodCoverage", "覆盖率检查");
    
    private final String toolName;
    private final String displayName;
    
    VerificationStep(String toolName, String displayName) {
        this.toolName = toolName;
        this.displayName = displayName;
    }
    
    public String getToolName() {
        return toolName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
