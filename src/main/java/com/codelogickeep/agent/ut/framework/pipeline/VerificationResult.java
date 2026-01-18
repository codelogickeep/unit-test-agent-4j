package com.codelogickeep.agent.ut.framework.pipeline;

/**
 * 验证管道执行结果
 */
public class VerificationResult {
    private boolean success;
    private VerificationStep failedStep;
    private String errorMessage;
    private String errorDetails;
    private String details;  // 通用详情，包括成功时的工具输出
    private double coverage;
    private boolean coverageThresholdMet;
    private int retryCount;
    
    public static VerificationResult success(double coverage, boolean thresholdMet) {
        VerificationResult result = new VerificationResult();
        result.success = true;
        result.coverage = coverage;
        result.coverageThresholdMet = thresholdMet;
        return result;
    }
    
    public static VerificationResult failure(VerificationStep step, String errorMessage) {
        VerificationResult result = new VerificationResult();
        result.success = false;
        result.failedStep = step;
        result.errorMessage = errorMessage;
        return result;
    }
    
    public static VerificationResult failure(VerificationStep step, String errorMessage, String errorDetails) {
        VerificationResult result = failure(step, errorMessage);
        result.errorDetails = errorDetails;
        return result;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public VerificationStep getFailedStep() {
        return failedStep;
    }
    
    public void setFailedStep(VerificationStep failedStep) {
        this.failedStep = failedStep;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public String getErrorDetails() {
        return errorDetails;
    }
    
    public void setErrorDetails(String errorDetails) {
        this.errorDetails = errorDetails;
    }
    
    public String getDetails() {
        return details;
    }
    
    public void setDetails(String details) {
        this.details = details;
    }
    
    public double getCoverage() {
        return coverage;
    }
    
    public void setCoverage(double coverage) {
        this.coverage = coverage;
    }
    
    public boolean isCoverageThresholdMet() {
        return coverageThresholdMet;
    }
    
    public void setCoverageThresholdMet(boolean coverageThresholdMet) {
        this.coverageThresholdMet = coverageThresholdMet;
    }
    
    public int getRetryCount() {
        return retryCount;
    }
    
    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
    
    public void incrementRetryCount() {
        this.retryCount++;
    }
    
    @Override
    public String toString() {
        if (success) {
            return String.format("VerificationResult[success=true, coverage=%.1f%%, thresholdMet=%s]",
                    coverage, coverageThresholdMet);
        } else {
            return String.format("VerificationResult[success=false, failedStep=%s, error=%s]",
                    failedStep, errorMessage);
        }
    }
}
