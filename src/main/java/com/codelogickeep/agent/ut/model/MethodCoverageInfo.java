package com.codelogickeep.agent.ut.model;

/**
 * 方法覆盖率信息
 */
public class MethodCoverageInfo {
    private String methodName;
    private String priority;
    private double lineCoverage;
    private double branchCoverage;
    private boolean needsTest;

    public MethodCoverageInfo(String methodName, String priority, double lineCoverage, double branchCoverage) {
        this.methodName = methodName;
        this.priority = priority;
        this.lineCoverage = lineCoverage;
        this.branchCoverage = branchCoverage;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getPriority() {
        return priority;
    }

    public double getLineCoverage() {
        return lineCoverage;
    }

    public double getBranchCoverage() {
        return branchCoverage;
    }

    public boolean isNeedsTest() {
        return needsTest;
    }

    public void setNeedsTest(boolean needsTest) {
        this.needsTest = needsTest;
    }

    public double getOverallCoverage() {
        return (lineCoverage + branchCoverage) / 2;
    }

    @Override
    public String toString() {
        return String.format("%s [%s] - Line: %.1f%%, Branch: %.1f%%",
                methodName, priority, lineCoverage, branchCoverage);
    }
}
