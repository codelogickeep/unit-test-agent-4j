package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.config.GovernanceConfig;
import com.codelogickeep.agent.ut.governance.ToolGovernanceProxy;
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

    @SuppressWarnings("unchecked")
    public static List<Object> loadAndWrapTools(AppConfig appConfig, GovernanceConfig governanceConfig, String knowledgeBasePath) {
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

                // Find the interface that extends AgentTool to proxy it
                // We assume the class implements an interface that extends AgentTool.
                // We need to proxy that specific interface.
                Class<?>[] interfaces = clazz.getInterfaces();
                Class<AgentTool> targetInterface = null;
                
                for (Class<?> iface : interfaces) {
                    if (AgentTool.class.isAssignableFrom(iface) && iface != AgentTool.class) {
                         // This is likely the specific tool interface (e.g. FileSystemTool)
                         targetInterface = (Class<AgentTool>) iface;
                         break;
                    }
                }

                if (targetInterface != null) {
                    // Wrap with Governance Proxy
                    Object proxy = ToolGovernanceProxy.createProxy(instance, targetInterface, governanceConfig);
                    tools.add(proxy);
                    log.info("Registered Tool: {} (Proxied via {})", clazz.getSimpleName(), targetInterface.getSimpleName());
                } else {
                    log.warn("Skipping Tool {}: Could not find specific interface extending AgentTool.", clazz.getSimpleName());
                }

            } catch (Exception e) {
                log.error("Failed to instantiate tool: {}", clazz.getName(), e);
            }
        }
        
        return tools;
    }
}
