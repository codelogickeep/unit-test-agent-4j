package com.codelogickeep.agent.ut.framework.phase;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.config.AppConfig.WorkflowConfig;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhaseManagerTest {

    private PhaseManager phaseManager;
    private ToolRegistry toolRegistry;
    private AppConfig config;
    private List<Object> allTools;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
        allTools = new ArrayList<>();

        config = mock(AppConfig.class);
        WorkflowConfig workflowConfig = mock(WorkflowConfig.class);
        when(config.getWorkflow()).thenReturn(workflowConfig);
        when(workflowConfig.isIterativeMode()).thenReturn(true);

        phaseManager = new PhaseManager(config, allTools);
    }

    @Test
    void testInitialPhaseIsAnalysisWhenIterativeMode() {
        assertEquals(WorkflowPhase.ANALYSIS, phaseManager.getCurrentPhase());
        assertTrue(phaseManager.isIterativeMode());
    }

    @Test
    void testInitialPhaseIsFullWhenNotIterativeMode() {
        WorkflowConfig workflowConfig = mock(WorkflowConfig.class);
        when(workflowConfig.isIterativeMode()).thenReturn(false);
        when(config.getWorkflow()).thenReturn(workflowConfig);

        PhaseManager disabledManager = new PhaseManager(config, allTools);
        assertEquals(WorkflowPhase.FULL, disabledManager.getCurrentPhase());
        assertFalse(disabledManager.isIterativeMode());
    }

    @Test
    void testSwitchToPhase() {
        phaseManager.switchToPhase(WorkflowPhase.GENERATION, toolRegistry);
        assertEquals(WorkflowPhase.GENERATION, phaseManager.getCurrentPhase());
    }

    @Test
    void testSwitchToNextPhase() {
        assertEquals(WorkflowPhase.ANALYSIS, phaseManager.getCurrentPhase());

        phaseManager.switchToNextPhase(toolRegistry);
        assertEquals(WorkflowPhase.GENERATION, phaseManager.getCurrentPhase());

        phaseManager.switchToNextPhase(toolRegistry);
        assertEquals(WorkflowPhase.VERIFICATION, phaseManager.getCurrentPhase());
    }

    @Test
    void testSmartSwitchToRepair() {
        phaseManager.switchToPhase(WorkflowPhase.VERIFICATION, toolRegistry);

        phaseManager.smartSwitch("compilation error occurred", toolRegistry);
        assertEquals(WorkflowPhase.REPAIR, phaseManager.getCurrentPhase());
    }

    @Test
    void testSmartSwitchNoRepairSignal() {
        WorkflowPhase currentPhase = phaseManager.getCurrentPhase();

        phaseManager.smartSwitch("all tests passed", toolRegistry);
        assertEquals(currentPhase, phaseManager.getCurrentPhase());
    }

    @Test
    void testGetContext() {
        PhaseContext context = phaseManager.getContext();
        assertNotNull(context);
        assertEquals(WorkflowPhase.ANALYSIS, context.getCurrentPhase());
    }
}
