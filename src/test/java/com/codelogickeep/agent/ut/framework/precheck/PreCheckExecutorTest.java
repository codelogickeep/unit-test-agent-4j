package com.codelogickeep.agent.ut.framework.precheck;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.engine.CoverageFeedbackEngine;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import com.codelogickeep.agent.ut.model.PreCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PreCheckExecutorTest {

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private AppConfig config;

    @Mock
    private AppConfig.WorkflowConfig workflowConfig;

    @Mock
    private CoverageFeedbackEngine feedbackEngine;

    private PreCheckExecutor executor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(config.getWorkflow()).thenReturn(workflowConfig);
        when(workflowConfig.getCoverageThreshold()).thenReturn(80);
        executor = new PreCheckExecutor(toolRegistry, config, feedbackEngine);
    }

    @Test
    void testExecuteWithNullProjectRoot() {
        PreCheckResult result = executor.execute(null, "/some/file.java");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Cannot determine project root"));
    }

    @Test
    void testConstructorInitialization() {
        assertNotNull(executor);

        PreCheckExecutor executorWithoutFeedback = new PreCheckExecutor(toolRegistry, config, null);
        assertNotNull(executorWithoutFeedback);
    }
}
