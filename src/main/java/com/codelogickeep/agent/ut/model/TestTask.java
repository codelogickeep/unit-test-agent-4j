package com.codelogickeep.agent.ut.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Represents a test generation task for a single source class.
 */
@Data
@Builder
public class TestTask {
    /** Path to the source file */
    private String sourceFilePath;

    /** Path to existing test file (null if needs to be created) */
    private String testFilePath;

    /** Fully qualified class name */
    private String className;

    /** Current line coverage percentage */
    private double currentCoverage;

    /** List of methods that need tests */
    private List<UncoveredMethod> uncoveredMethods;

    /** Whether this is a new test class or appending to existing */
    public boolean isNewTestClass() {
        return testFilePath == null;
    }

    /** Get simple class name */
    public String getSimpleClassName() {
        if (className == null) return null;
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }
}
