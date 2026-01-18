package com.codelogickeep.agent.ut.framework.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Prompt 模板加载器
 */
public class PromptTemplateLoader {
    private static final Logger log = LoggerFactory.getLogger(PromptTemplateLoader.class);

    /**
     * 从 classpath 加载模板
     */
    public static String loadTemplate(String templatePath) {
        if (templatePath == null) {
            log.warn("Template path is null");
            return "";
        }
        try (InputStream is = PromptTemplateLoader.class.getClassLoader().getResourceAsStream(templatePath)) {
            if (is == null) {
                log.warn("Template not found: {}", templatePath);
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to load template: {}", templatePath, e);
            return "";
        }
    }
}
