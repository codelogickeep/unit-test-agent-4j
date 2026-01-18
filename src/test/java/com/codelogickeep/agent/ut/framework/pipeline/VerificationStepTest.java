package com.codelogickeep.agent.ut.framework.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificationStepTest {
    
    @Test
    void testSyntaxCheckStep() {
        VerificationStep step = VerificationStep.SYNTAX_CHECK;
        assertEquals("checkSyntax", step.getToolName());
        assertEquals("语法检查", step.getDisplayName());
    }
    
    @Test
    void testLspCheckStep() {
        VerificationStep step = VerificationStep.LSP_CHECK;
        assertEquals("checkSyntaxWithLsp", step.getToolName());
        assertEquals("LSP语义检查", step.getDisplayName());
    }
    
    @Test
    void testCompileStep() {
        VerificationStep step = VerificationStep.COMPILE;
        assertEquals("compileProject", step.getToolName());
        assertEquals("编译项目", step.getDisplayName());
    }
    
    @Test
    void testTestStep() {
        VerificationStep step = VerificationStep.TEST;
        assertEquals("executeTest", step.getToolName());
        assertEquals("执行测试", step.getDisplayName());
    }
    
    @Test
    void testCoverageStep() {
        VerificationStep step = VerificationStep.COVERAGE;
        assertEquals("getSingleMethodCoverage", step.getToolName());
        assertEquals("覆盖率检查", step.getDisplayName());
    }
    
    @Test
    void testAllStepsExist() {
        VerificationStep[] steps = VerificationStep.values();
        assertEquals(5, steps.length);
    }
}
