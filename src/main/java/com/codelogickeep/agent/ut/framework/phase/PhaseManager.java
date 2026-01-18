package com.codelogickeep.agent.ut.framework.phase;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 阶段管理器 - 负责阶段切换和工具重新加载
 * 
 * 阶段切换在迭代模式下自动启用，用于减少 token 消耗
 */
public class PhaseManager {
    private static final Logger log = LoggerFactory.getLogger(PhaseManager.class);

    private final PhaseContext context;
    private final AppConfig config;
    private final List<Object> allTools;
    private final boolean iterativeMode;

    public PhaseManager(AppConfig config, List<Object> allTools) {
        this.config = config;
        this.allTools = allTools;
        // 在迭代模式下自动启用阶段切换
        this.iterativeMode = config.getWorkflow() != null && config.getWorkflow().isIterativeMode();

        // 初始化阶段上下文：迭代模式从 ANALYSIS 开始，否则使用 FULL
        WorkflowPhase initialPhase = iterativeMode ? WorkflowPhase.ANALYSIS : WorkflowPhase.FULL;
        this.context = new PhaseContext(initialPhase);

        log.info("PhaseManager initialized: iterativeMode={}, initial phase: {}",
                iterativeMode, initialPhase);
    }

    /**
     * 初始化工具 - 加载初始阶段的工具到 ToolRegistry
     * 必须在构造函数之后调用一次
     */
    public void initializeTools(ToolRegistry toolRegistry) {
        WorkflowPhase currentPhase = context.getCurrentPhase();
        log.info("Initializing tools for phase: {}", currentPhase);
        reloadToolsForPhase(currentPhase, toolRegistry);
    }

    /**
     * 切换到指定阶段
     */
    public void switchToPhase(WorkflowPhase phase, ToolRegistry toolRegistry) {
        if (!iterativeMode) {
            log.debug("Not in iterative mode, staying in FULL mode");
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
        if (!iterativeMode) {
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

    public boolean isIterativeMode() {
        return iterativeMode;
    }
}
