package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.annotation.Tool;
import com.codelogickeep.agent.ut.framework.model.ToolDefinition;
import com.codelogickeep.agent.ut.framework.tool.ToolRegistry;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ToolFactory {
    private static final Logger log = LoggerFactory.getLogger(ToolFactory.class);

    /**
     * 加载所有工具（原有方法，保持兼容）
     */
    public static List<Object> loadAndWrapTools(AppConfig appConfig, String knowledgeBasePath) {
        return loadAndWrapTools(appConfig, knowledgeBasePath, null);
    }

    /**
     * 根据 skill 名称加载工具
     * @param appConfig 应用配置
     * @param knowledgeBasePath 知识库路径
     * @param skillName skill 名称，null 或空表示加载全部工具
     * @return 工具列表
     */
    public static List<Object> loadAndWrapTools(AppConfig appConfig, String knowledgeBasePath, String skillName) {
        List<Object> allTools = loadAllTools(appConfig, knowledgeBasePath);
        
        // 如果指定了 skill，则过滤工具
        if (skillName != null && !skillName.isEmpty()) {
            List<Object> filteredTools = filterToolsBySkill(allTools, appConfig, skillName);
            if (!filteredTools.isEmpty()) {
                log.info("Using skill '{}' with {} tools (filtered from {})", 
                        skillName, filteredTools.size(), allTools.size());
                printToolSpecifications(filteredTools);
                return filteredTools;
            } else {
                log.warn("Skill '{}' not found or has no tools defined, using all tools", skillName);
            }
        }
        
        printToolSpecifications(allTools);
        return allTools;
    }

    /**
     * 加载所有可用工具（内部方法）
     */
    private static List<Object> loadAllTools(AppConfig appConfig, String knowledgeBasePath) {
        List<Object> tools = new ArrayList<>();
        
        // 先创建基础工具的引用，用于依赖注入
        CodeAnalyzerTool codeAnalyzerTool = null;
        CoverageTool coverageTool = null;
        
        // Scan for implementations of AgentTool
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage("com.codelogickeep.agent.ut.tools"))
                .setScanners(Scanners.SubTypes));

        Set<Class<? extends AgentTool>> toolClasses = reflections.getSubTypesOf(AgentTool.class);

        // 检查是否启用 LSP
        boolean useLsp = appConfig.getWorkflow() != null && appConfig.getWorkflow().isUseLsp();
        // 检查是否启用迭代模式
        boolean iterativeMode = appConfig.getWorkflow() != null && appConfig.getWorkflow().isIterativeMode();

        // 第一轮：实例化不需要依赖的工具
        for (Class<? extends AgentTool> clazz : toolClasses) {
            // Skip interfaces and abstract classes
            if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }
            
            String className = clazz.getSimpleName();
            
            // 根据配置决定是否加载 LSP 相关工具
            if (className.equals("LspSyntaxCheckerTool") && !useLsp) {
                log.info("Skipping LspSyntaxCheckerTool (use-lsp not enabled in config)");
                continue;
            }
            
            // 跳过需要依赖注入的工具（第二轮处理）
            if (className.equals("MethodIteratorTool")) {
                continue;
            }

            try {
                // Instantiate
                AgentTool instance = clazz.getDeclaredConstructor().newInstance();
                log.debug("Found Agent Tool: {}", clazz.getSimpleName());

                // Special initialization for KnowledgeBaseTool
                if (instance instanceof KnowledgeBaseTool) {
                    ((KnowledgeBaseTool) instance).init(appConfig, knowledgeBasePath);
                }
                
                // 保存引用供后续依赖注入使用
                if (instance instanceof CodeAnalyzerTool) {
                    codeAnalyzerTool = (CodeAnalyzerTool) instance;
                }
                if (instance instanceof CoverageTool) {
                    coverageTool = (CoverageTool) instance;
                }

                // Register Tool Directly (No Governance Proxy)
                tools.add(instance);
                log.info("Registered Tool: {}", clazz.getSimpleName());

            } catch (Exception e) {
                log.error("Failed to instantiate tool: {}", clazz.getName(), e);
            }
        }
        
        // 第二轮：创建需要依赖注入的工具
        // MethodIteratorTool: 仅在迭代模式下加载
        if (iterativeMode && codeAnalyzerTool != null && coverageTool != null) {
            try {
                MethodIteratorTool methodIteratorTool = new MethodIteratorTool(codeAnalyzerTool, coverageTool);
                tools.add(methodIteratorTool);
                log.info("Registered Tool: MethodIteratorTool (iterative mode enabled)");
            } catch (Exception e) {
                log.error("Failed to instantiate MethodIteratorTool", e);
            }
        } else if (iterativeMode) {
            log.warn("MethodIteratorTool requires CodeAnalyzerTool and CoverageTool, but they are not available");
        }
        
        return tools;
    }

    /**
     * 根据 skill 配置过滤工具
     * 
     * @deprecated 阶段切换已迁移至 {@link com.codelogickeep.agent.ut.framework.phase.WorkflowPhase}，
     *             不再使用 agent.yml 中的 skills 配置。
     *             请使用 {@link com.codelogickeep.agent.ut.framework.phase.PhaseManager#switchToPhase}
     * 
     * @param allTools 所有工具
     * @param appConfig 配置
     * @param skillName skill 名称
     * @return 过滤后的工具列表
     */
    @Deprecated
    public static List<Object> filterToolsBySkill(List<Object> allTools, AppConfig appConfig, String skillName) {
        if (appConfig.getSkills() == null || appConfig.getSkills().isEmpty()) {
            return allTools;
        }
        
        // 查找匹配的 skill 配置
        AppConfig.SkillConfig skillConfig = appConfig.getSkills().stream()
                .filter(s -> skillName.equals(s.getName()))
                .findFirst()
                .orElse(null);
        
        if (skillConfig == null) {
            log.warn("Skill '{}' not found in configuration", skillName);
            return allTools;
        }
        
        // 如果 tools 为空或 null，返回全部工具
        List<String> toolNames = skillConfig.getTools();
        if (toolNames == null || toolNames.isEmpty()) {
            log.info("Skill '{}' has no specific tools, using all tools", skillName);
            return allTools;
        }
        
        // 过滤工具
        Set<String> toolNameSet = new HashSet<>(toolNames);
        List<Object> filteredTools = allTools.stream()
                .filter(tool -> toolNameSet.contains(tool.getClass().getSimpleName()))
                .collect(Collectors.toList());
        
        log.info("Skill '{}': {} tools requested, {} tools matched", 
                skillName, toolNames.size(), filteredTools.size());
        
        // 警告未找到的工具
        Set<String> foundToolNames = filteredTools.stream()
                .map(t -> t.getClass().getSimpleName())
                .collect(Collectors.toSet());
        toolNames.stream()
                .filter(name -> !foundToolNames.contains(name))
                .forEach(name -> log.warn("Tool '{}' specified in skill '{}' was not found", name, skillName));
        
        return filteredTools;
    }

    /**
     * 按工具名称列表过滤工具
     * @param allTools 所有工具
     * @param toolNames 工具类名列表
     * @return 过滤后的工具列表
     */
    public static List<Object> filterToolsByNames(List<Object> allTools, List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return allTools;
        }
        
        Set<String> toolNameSet = new HashSet<>(toolNames);
        return allTools.stream()
                .filter(tool -> toolNameSet.contains(tool.getClass().getSimpleName()))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有可用的 skill 名称
     * 
     * @deprecated 阶段切换已迁移至 {@link com.codelogickeep.agent.ut.framework.phase.WorkflowPhase}，
     *             请使用 {@code WorkflowPhase.values()} 获取所有阶段
     */
    @Deprecated
    public static List<String> getAvailableSkillNames(AppConfig appConfig) {
        if (appConfig.getSkills() == null) {
            return List.of();
        }
        return appConfig.getSkills().stream()
                .map(AppConfig.SkillConfig::getName)
                .collect(Collectors.toList());
    }

    /**
     * 打印工具规格（使用自研框架）
     */
    private static void printToolSpecifications(List<Object> tools) {
        System.out.println(">>> Loaded Tools & Specifications:");
        
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(tools);
        
        for (Object tool : tools) {
            System.out.println("--------------------------------------------------");
            System.out.println("Tool Class: " + tool.getClass().getSimpleName());
            
            // 获取该工具类中标注了 @Tool 的方法
            for (Method method : tool.getClass().getMethods()) {
                Tool toolAnnotation = method.getAnnotation(Tool.class);
                if (toolAnnotation != null) {
                    System.out.println("  Function: " + method.getName());
                    if (!toolAnnotation.value().isEmpty()) {
                        System.out.println("  Description: " + toolAnnotation.value());
                    }
                    System.out.println("  Parameters: " + method.getParameterCount());
                }
            }
        }
        System.out.println("--------------------------------------------------");
        System.out.println("Total tools: " + tools.size() + ", Total functions: " + registry.size());
    }
}
