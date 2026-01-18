package com.codelogickeep.agent.ut.framework.phase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhaseContextTest {

    private PhaseContext context;

    @BeforeEach
    void setUp() {
        context = new PhaseContext(WorkflowPhase.ANALYSIS);
    }

    @Test
    void testInitialPhase() {
        assertEquals(WorkflowPhase.ANALYSIS, context.getCurrentPhase());
    }

    @Test
    void testSetCurrentPhase() {
        context.setCurrentPhase(WorkflowPhase.GENERATION);
        assertEquals(WorkflowPhase.GENERATION, context.getCurrentPhase());
    }

    @Test
    void testPhaseTransitionCount() {
        assertEquals(0, context.getPhaseTransitionCount());

        context.setCurrentPhase(WorkflowPhase.GENERATION);
        assertEquals(1, context.getPhaseTransitionCount());

        context.setCurrentPhase(WorkflowPhase.VERIFICATION);
        assertEquals(2, context.getPhaseTransitionCount());
    }

    @Test
    void testPhaseTransitionCountNotIncrementedForSamePhase() {
        context.setCurrentPhase(WorkflowPhase.ANALYSIS);
        assertEquals(0, context.getPhaseTransitionCount());
    }

    @Test
    void testPutAndGetData() {
        assertNull(context.getData("key1"));

        context.putData("key1", "value1");
        assertEquals("value1", context.getData("key1"));

        context.putData("key2", 123);
        assertEquals(123, context.getData("key2"));
    }

    @Test
    void testGetDataWithType() {
        context.putData("stringKey", "value");
        context.putData("intKey", 42);

        assertEquals("value", context.getData("stringKey", String.class));
        assertEquals(42, context.getData("intKey", Integer.class));
        assertNull(context.getData("stringKey", Integer.class));
    }

    @Test
    void testClearData() {
        context.putData("key1", "value1");
        context.putData("key2", "value2");

        context.clearData();

        assertNull(context.getData("key1"));
        assertNull(context.getData("key2"));
    }
}
