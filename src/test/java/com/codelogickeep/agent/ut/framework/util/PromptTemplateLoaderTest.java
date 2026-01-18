package com.codelogickeep.agent.ut.framework.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptTemplateLoaderTest {

    @Test
    void testLoadExistingTemplate() {
        String content = PromptTemplateLoader.loadTemplate("prompts/system-prompt.st");

        assertNotNull(content);
        assertFalse(content.isEmpty());
        assertTrue(content.contains("You are an expert Java QA Engineer"));
    }

    @Test
    void testLoadNonExistentTemplate() {
        String content = PromptTemplateLoader.loadTemplate("prompts/non-existent.txt");

        assertEquals("", content);
    }

    @Test
    void testLoadTemplateWithNullPath() {
        String content = PromptTemplateLoader.loadTemplate(null);

        assertEquals("", content);
    }
}
