package com.codelogickeep.agent.ut.framework.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificationResultTest {
    
    @Test
    void testSuccessFactory() {
        VerificationResult result = VerificationResult.success(85.5, true);
        
        assertTrue(result.isSuccess());
        assertEquals(85.5, result.getCoverage(), 0.01);
        assertTrue(result.isCoverageThresholdMet());
        assertNull(result.getFailedStep());
        assertNull(result.getErrorMessage());
    }
    
    @Test
    void testSuccessFactoryThresholdNotMet() {
        VerificationResult result = VerificationResult.success(50.0, false);
        
        assertTrue(result.isSuccess());
        assertEquals(50.0, result.getCoverage(), 0.01);
        assertFalse(result.isCoverageThresholdMet());
    }
    
    @Test
    void testFailureFactory() {
        VerificationResult result = VerificationResult.failure(
                VerificationStep.COMPILE, "编译失败");
        
        assertFalse(result.isSuccess());
        assertEquals(VerificationStep.COMPILE, result.getFailedStep());
        assertEquals("编译失败", result.getErrorMessage());
        assertNull(result.getErrorDetails());
    }
    
    @Test
    void testFailureFactoryWithDetails() {
        VerificationResult result = VerificationResult.failure(
                VerificationStep.SYNTAX_CHECK, "语法错误", "第10行缺少分号");
        
        assertFalse(result.isSuccess());
        assertEquals(VerificationStep.SYNTAX_CHECK, result.getFailedStep());
        assertEquals("语法错误", result.getErrorMessage());
        assertEquals("第10行缺少分号", result.getErrorDetails());
    }
    
    @Test
    void testIncrementRetryCount() {
        VerificationResult result = new VerificationResult();
        assertEquals(0, result.getRetryCount());
        
        result.incrementRetryCount();
        assertEquals(1, result.getRetryCount());
        
        result.incrementRetryCount();
        assertEquals(2, result.getRetryCount());
    }
    
    @Test
    void testSettersAndGetters() {
        VerificationResult result = new VerificationResult();
        
        result.setSuccess(true);
        assertTrue(result.isSuccess());
        
        result.setFailedStep(VerificationStep.TEST);
        assertEquals(VerificationStep.TEST, result.getFailedStep());
        
        result.setErrorMessage("test error");
        assertEquals("test error", result.getErrorMessage());
        
        result.setErrorDetails("details");
        assertEquals("details", result.getErrorDetails());
        
        result.setCoverage(75.5);
        assertEquals(75.5, result.getCoverage(), 0.01);
        
        result.setCoverageThresholdMet(true);
        assertTrue(result.isCoverageThresholdMet());
        
        result.setRetryCount(3);
        assertEquals(3, result.getRetryCount());
    }
    
    @Test
    void testToStringSuccess() {
        VerificationResult result = VerificationResult.success(90.0, true);
        String str = result.toString();
        
        assertTrue(str.contains("success=true"));
        assertTrue(str.contains("90.0%"));
        assertTrue(str.contains("thresholdMet=true"));
    }
    
    @Test
    void testToStringFailure() {
        VerificationResult result = VerificationResult.failure(
                VerificationStep.COMPILE, "编译错误");
        String str = result.toString();
        
        assertTrue(str.contains("success=false"));
        assertTrue(str.contains("COMPILE"));
        assertTrue(str.contains("编译错误"));
    }
}
