package com.codelogickeep.agent.ut.model;

import lombok.Builder;
import lombok.Data;

/**
 * Represents an uncovered or partially covered method that needs tests.
 */
@Data
@Builder
public class UncoveredMethod {
    /** Method name */
    private String methodName;

    /** Method signature (e.g., "calculate(int, int)") */
    private String signature;

    /** Line coverage percentage (0-100) */
    private double lineCoverage;

    /** Branch coverage percentage (0-100) */
    private double branchCoverage;

    /** Method complexity (cyclomatic) */
    private int complexity;

    /** Return type */
    private String returnType;

    /** Parameter types (comma-separated) */
    private String parameterTypes;

    /** Whether this method is a constructor */
    public boolean isConstructor() {
        return "<init>".equals(methodName) || "constructor".equals(methodName);
    }

    /** Get display name */
    public String getDisplayName() {
        return isConstructor() ? "constructor" : methodName;
    }
}
