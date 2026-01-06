package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.infra.AgentTool;
import com.codelogickeep.agent.ut.infra.KnowledgeBaseToolImpl;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ToolFactory {
    private static final Logger log = LoggerFactory.getLogger(ToolFactory.class);

    public static List<Object> loadAndWrapTools(AppConfig appConfig, String knowledgeBasePath) {
        List<Object> tools = new ArrayList<>();
        
        // Scan for implementations of AgentTool
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage("com.codelogickeep.agent.ut.infra"))
                .setScanners(Scanners.SubTypes));

        Set<Class<? extends AgentTool>> toolClasses = reflections.getSubTypesOf(AgentTool.class);

        for (Class<? extends AgentTool> clazz : toolClasses) {
            // Skip interfaces and abstract classes
            if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }

            try {
                // Instantiate
                AgentTool instance = clazz.getDeclaredConstructor().newInstance();
                log.debug("Found Agent Tool: {}", clazz.getSimpleName());

                // Special initialization for KnowledgeBaseTool
                if (instance instanceof KnowledgeBaseToolImpl) {
                    ((KnowledgeBaseToolImpl) instance).init(appConfig, knowledgeBasePath);
                }

                // Register Tool Directly (No Governance Proxy)
                tools.add(instance);
                log.info("Registered Tool: {}", clazz.getSimpleName());

            } catch (Exception e) {
                log.error("Failed to instantiate tool: {}", clazz.getName(), e);
            }
        }
        
        return tools;
    }
}
