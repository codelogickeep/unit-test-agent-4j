package com.codelogickeep.agent.ut.model;

import com.codelogickeep.agent.ut.engine.CoverageFeedbackEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 预检查结果
 */
public class PreCheckResult {
    private boolean success;
    private String errorMessage;
    private String coverageInfo;
    private boolean hasExistingTests;
    private List<MethodCoverageInfo> methodCoverages;
    private CoverageFeedbackEngine.FeedbackResult feedbackResult;

    public static PreCheckResult success(String coverageInfo, boolean hasExistingTests,
                                         List<MethodCoverageInfo> methodCoverages) {
        PreCheckResult r = new PreCheckResult();
        r.success = true;
        r.coverageInfo = coverageInfo;
        r.hasExistingTests = hasExistingTests;
        r.methodCoverages = methodCoverages != null ? methodCoverages : new ArrayList<>();
        return r;
    }

    public static PreCheckResult failure(String error) {
        PreCheckResult r = new PreCheckResult();
        r.success = false;
        r.errorMessage = error;
        return r;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getCoverageInfo() {
        return coverageInfo;
    }

    public boolean isHasExistingTests() {
        return hasExistingTests;
    }

    public List<MethodCoverageInfo> getMethodCoverages() {
        return methodCoverages;
    }

    public CoverageFeedbackEngine.FeedbackResult getFeedbackResult() {
        return feedbackResult;
    }

    public void setFeedbackResult(CoverageFeedbackEngine.FeedbackResult feedbackResult) {
        this.feedbackResult = feedbackResult;
    }

    public List<MethodCoverageInfo> getMethodsSortedByCoverage() {
        if (methodCoverages == null || methodCoverages.isEmpty()) {
            return new ArrayList<>();
        }
        return methodCoverages.stream()
                .sorted((a, b) -> Double.compare(a.getOverallCoverage(), b.getOverallCoverage()))
                .collect(java.util.stream.Collectors.toList());
    }
}
