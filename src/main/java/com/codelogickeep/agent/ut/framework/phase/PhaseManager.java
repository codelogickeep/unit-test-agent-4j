package com.codelogickeep.agent.ut.framework.phase;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 阶段管理器 - 负责阶段切换和工具重新加载
 */
public class PhaseManager {
    private static final Logger log = LoggerFactory.getLogger(PhaseManager.class);

    private final PhaseContext context;
    private final AppConfig config;
    private final List<Object> allTools;
    private final boolean enablePhaseSwitching;

    public PhaseManager(AppConfig config, List<Object> allTools) {
        this.config = config;
        this.allTools = allTools;
        this.enablePhaseSwitching = config.getWorkflow().isEnablePhaseSwitching();

        // 初始化阶段上下文
        WorkflowPhase initialPhase = enablePhaseSwitching ? WorkflowPhase.ANALYSIS : WorkflowPhase.FULL;
        this.context = new PhaseContext(initialPhase);

        log.info("PhaseManager initialized with phase switching: {}, initial phase: {}",
                enablePhaseSwitching, initialPhase);
    }

    /**
     * 切换到指定阶段
     */
    public void switchToPhase(WorkflowPhase phase, ToolRegistry toolRegistry) {
        if (!enablePhaseSwitching) {
            log.debug("Phase switching disabled, staying in FULL mode");
            return;
        }

        WorkflowPhase oldPhase = context.getCurrentPhase();
        if (oldPhase == phase) {
            log.debug("Already in phase: {}", phase);
            return;
        }

        log.info("Switching phase: {} -> {}", oldPhase, phase);
        context.setCurrentPhase(phase);
        reloadToolsForPhase(phase, toolRegistry);
    }

    /**
     * 切换到下一个阶段
     */
    public void switchToNextPhase(ToolRegistry toolRegistry) {
        WorkflowPhase nextPhase = context.getCurrentPhase().next();
        switchToPhase(nextPhase, toolRegistry);
    }

    /**
     * 根据 LLM 响应智能切换阶段
     */
    public void smartSwitch(String llmResponse, ToolRegistry toolRegistry) {
        if (!enablePhaseSwitching) {
            return;
        }

        if (WorkflowPhase.shouldSwitchToRepair(llmResponse)) {
            log.info("Detected repair signal in LLM response, switching to REPAIR phase");
            switchToPhase(WorkflowPhase.REPAIR, toolRegistry);
        }
    }

    /**
     * 重新加载工具
     */
    private void reloadToolsForPhase(WorkflowPhase phase, ToolRegistry toolRegistry) {
        toolRegistry.clear();

        if (phase == WorkflowPhase.FULL) {
            // 加载所有工具
            toolRegistry.registerAll(allTools);
            log.info("Loaded all {} tools for FULL phase", toolRegistry.size());
        } else {
            // 加载阶段特定工具
            List<String> phaseToolNames = phase.getToolNames();
            List<Object> phaseTools = filterToolsByNames(allTools, phaseToolNames);
            toolRegistry.registerAll(phaseTools);
            log.info("Loaded {} tools for {} phase: {}",
                    toolRegistry.size(), phase, phaseToolNames);
        }
    }

    /**
     * 根据类名过滤工具
     */
    private List<Object> filterToolsByNames(List<Object> tools, List<String> names) {
        return tools.stream()
                .filter(tool -> names.contains(tool.getClass().getSimpleName()))
                .toList();
    }

    public PhaseContext getContext() {
        return context;
    }

    public WorkflowPhase getCurrentPhase() {
        return context.getCurrentPhase();
    }

    public boolean isEnablePhaseSwitching() {
        return enablePhaseSwitching;
    }
}
