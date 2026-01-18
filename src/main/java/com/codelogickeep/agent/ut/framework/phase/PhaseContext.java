package com.codelogickeep.agent.ut.framework.phase;

import java.util.HashMap;
import java.util.Map;

/**
 * 阶段上下文 - 跟踪当前阶段和阶段间数据
 */
public class PhaseContext {
    private WorkflowPhase currentPhase;
    private final Map<String, Object> phaseData;
    private int phaseTransitionCount;

    public PhaseContext(WorkflowPhase initialPhase) {
        this.currentPhase = initialPhase;
        this.phaseData = new HashMap<>();
        this.phaseTransitionCount = 0;
    }

    public WorkflowPhase getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(WorkflowPhase phase) {
        if (this.currentPhase != phase) {
            this.phaseTransitionCount++;
        }
        this.currentPhase = phase;
    }

    public void putData(String key, Object value) {
        phaseData.put(key, value);
    }

    public Object getData(String key) {
        return phaseData.get(key);
    }

    public <T> T getData(String key, Class<T> type) {
        Object value = phaseData.get(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    public int getPhaseTransitionCount() {
        return phaseTransitionCount;
    }

    public void clearData() {
        phaseData.clear();
    }
}
