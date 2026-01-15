package com.codelogickeep.agent.ut.engine;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.tools.StyleAnalyzerTool;
import com.codelogickeep.agent.ut.tools.ToolFactory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Agent 编排器 - 协调 LLM 与工具的交互
 * 
 * 重构后的特性：
 * - 使用 RetryExecutor 进行重试
 * - 使用 StreamingResponseHandler 处理流式响应
 * - 支持 DynamicPromptBuilder 动态构建 Prompt
 * - 集成 RepairTracker 跟踪修复历史
 */
@Slf4j
public class AgentOrchestrator {

    private final AppConfig config;
    private final StreamingChatModel streamingLlm;
    private final List<Object> allTools;  // 所有可用工具
    private List<Object> activeTools;     // 当前激活的工具（可能是子集）
    private String currentSkill;          // 当前使用的 skill 名称
    
    // 可选的增强组件
    private DynamicPromptBuilder dynamicPromptBuilder;
    private RepairTracker repairTracker;
    private RetryExecutor retryExecutor;
    private StreamingResponseHandler streamingHandler;

    public AgentOrchestrator(AppConfig config,
            StreamingChatModel streamingLlm,
            List<Object> tools) {
        this.config = config;
        this.streamingLlm = streamingLlm;
        this.allTools = tools;
        this.activeTools = tools;  // 默认使用全部工具
        
        // 初始化辅助组件
        initializeComponents();
    }

    private void initializeComponents() {
        // 初始化重试执行器
        int maxRetries = config.getWorkflow() != null ? config.getWorkflow().getMaxRetries() : 3;
        this.retryExecutor = new RetryExecutor(
                RetryExecutor.RetryConfig.builder()
                        .maxRetries(maxRetries)
                        .initialWaitMs(1000)
                        .backoffMultiplier(2.0)
                        .maxWaitMs(30000)
                        .logStackTrace(false)
                        .build()
        );

        // 初始化流式响应处理器
        this.streamingHandler = new StreamingResponseHandler();

        // 初始化修复跟踪器
        this.repairTracker = new RepairTracker();

        // 尝试初始化动态 Prompt 构建器
        try {
            this.dynamicPromptBuilder = new DynamicPromptBuilder(config);
            log.debug("DynamicPromptBuilder initialized");
        } catch (Exception e) {
            log.debug("DynamicPromptBuilder not available: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T findTool(Class<T> toolClass) {
        for (Object tool : activeTools) {
            if (toolClass.isInstance(tool)) {
                return (T) tool;
            }
        }
        return null;
    }

    public void run(String targetFile) {
        run(targetFile, null);
    }

    public void run(String targetFile, String taskContext) {
        // 检查是否启用迭代模式
        boolean iterativeMode = config.getWorkflow() != null && config.getWorkflow().isIterativeMode();
        
        if (iterativeMode) {
            runIterative(targetFile, taskContext);
        } else {
            runTraditional(targetFile, taskContext);
        }
    }

    /**
     * 传统模式：一次性生成所有测试
     */
    private void runTraditional(String targetFile, String taskContext) {
        log.info("Starting Agent orchestration (traditional mode) for target: {}", targetFile);
        
        // 提取项目根路径
        String projectRoot = extractProjectRoot(targetFile);

        // 加载 System Prompt（优先使用动态构建器）
        String systemPrompt = buildSystemPrompt(projectRoot);
        
        // 打印注册的工具
        logRegisteredTools();

        // 使用重试执行器执行任务
        RetryExecutor.ExecutionResult<OrchestrationResult> result = retryExecutor.execute(
                () -> {
                    UnitTestAssistant assistant = createAssistant(systemPrompt);
                    return executeTask(assistant, targetFile, taskContext);
                },
                "Test Generation for " + extractFileName(targetFile)
        );

        // 处理结果
        if (result.isSuccess()) {
            OrchestrationResult taskResult = result.getResult();
            log.info("Agent completed task successfully. {}", taskResult.getSummary());
        } else {
            log.error("Agent failed to complete task. {}", result.getSummary());
            
            if (result.getLastException() != null) {
                log.debug("Final exception:", result.getLastException());
            }
        }
    }

    /**
     * 迭代模式：每个方法独立执行，每次创建新的 assistant
     * 
     * 流程：
     * 1. 第一轮：初始化（分析方法、创建测试文件骨架）
     * 2. 循环：每个方法单独处理（新 assistant = 清空上下文）
     * 3. 最后：汇总报告
     */
    private void runIterative(String targetFile, String taskContext) {
        log.info("Starting Agent orchestration (ITERATIVE mode) for target: {}", targetFile);
        
        String projectRoot = extractProjectRoot(targetFile);
        String systemPrompt = buildSystemPrompt(projectRoot);
        
        logRegisteredTools();

        // 获取 LLM 协议，用于智谱 AI 兼容性处理
        String llmProtocol = config.getLlm() != null ? config.getLlm().getProtocol() : "";
        boolean isZhipuAI = "openai-zhipu".equalsIgnoreCase(llmProtocol);
        
        // ===== Phase 1: 初始化 =====
        log.info(">>> Phase 1: Initialization");
        String initPrompt = buildIterativeInitPrompt(targetFile);
        
        // 智谱 AI 需要更小的窗口大小来避免 1214 错误
        int phase1MemorySize = isZhipuAI ? 8 : 15;
        
        RetryExecutor.ExecutionResult<OrchestrationResult> initResult = retryExecutor.execute(
                () -> {
                    UnitTestAssistant assistant = createAssistant(systemPrompt, phase1MemorySize);
                    return executeTask(assistant, targetFile, initPrompt);
                },
                "Iterative Init for " + extractFileName(targetFile)
        );

        if (!initResult.isSuccess()) {
            log.error("Iterative initialization failed: {}", initResult.getSummary());
            return;
        }

        // ===== Phase 2: 逐方法迭代 =====
        int maxIterations = 20;  // 防止无限循环
        int iteration = 0;
        
        // 智谱 AI 需要最小的窗口大小（每次调用都几乎是全新的）
        // 原因：LangChain4j 生成的 assistant 消息在有 tool_calls 时会省略 content 字段
        // 智谱 AI 要求 content 字段必须存在，这是个兼容性问题
        int phase2MemorySize = isZhipuAI ? 3 : 10;
        if (isZhipuAI) {
            log.info("Using small ChatMemory (size={}) for Zhipu AI to minimize 1214 errors", phase2MemorySize);
        }
        
        while (iteration < maxIterations) {
            iteration++;
            log.info(">>> Phase 2: Method Iteration #{}", iteration);
            
            // 每个方法创建新的 assistant（清空上下文！）
            String methodPrompt = buildIterativeMethodPrompt(targetFile, iteration);
            final int memSize = phase2MemorySize;
            
            RetryExecutor.ExecutionResult<OrchestrationResult> methodResult = retryExecutor.execute(
                    () -> {
                        // ⚠️ 新 assistant = 新上下文
                        UnitTestAssistant assistant = createAssistant(systemPrompt, memSize);
                        return executeTask(assistant, targetFile, methodPrompt);
                    },
                    "Method #" + iteration
            );

            if (!methodResult.isSuccess()) {
                log.warn("Method iteration #{} failed, continuing...", iteration);
            }

            // 检查迭代是否完成（通过检测输出内容）
            String content = methodResult.isSuccess() && methodResult.getResult() != null 
                    ? methodResult.getResult().getContent() : "";
            if (content.contains("ITERATION_COMPLETE") || content.contains("getIterationProgress")) {
                log.info(">>> Iteration completed after {} methods", iteration);
                break;
            }
        }

        // ===== Phase 3: 汇总 =====
        log.info(">>> Phase 3: Summary");
        String summaryPrompt = "Call getIterationProgress() to show the final summary of all tested methods.";
        
        RetryExecutor.ExecutionResult<OrchestrationResult> summaryResult = retryExecutor.execute(
                () -> {
                    UnitTestAssistant assistant = createAssistant(systemPrompt, 5);
                    return executeTask(assistant, targetFile, summaryPrompt);
                },
                "Summary"
        );

        if (summaryResult.isSuccess()) {
            log.info("Iterative test generation completed successfully.");
        } else {
            log.warn("Summary phase failed, but tests may have been generated.");
        }
    }

    /**
     * 构建迭代模式初始化提示
     */
    private String buildIterativeInitPrompt(String targetFile) {
        return String.format("""
            ## ITERATIVE MODE - PHASE 1: INITIALIZATION
            
            Target file: %s
            
            Please complete these steps:
            1. Check if test directory exists (directoryExists)
            2. Check if test file exists (fileExists)  
            3. Read the source file (readFile)
            4. Analyze method priorities (getPriorityMethods)
            5. Initialize iteration (initMethodIteration)
            6. Create the test file skeleton if it doesn't exist (writeFile with imports, class declaration, @Mock fields, @BeforeEach setup)
            
            After initialization, call getNextMethod() to get the first method to test.
            Then STOP and wait for next instruction.
            """, targetFile);
    }

    /**
     * 构建迭代模式方法处理提示
     */
    private String buildIterativeMethodPrompt(String targetFile, int iteration) {
        return String.format("""
            ## ITERATIVE MODE - PHASE 2: METHOD #%d
            
            Target file: %s
            
            ⚠️ THIS IS A FRESH CONTEXT - Previous conversation is cleared.
            
            Please complete these steps:
            1. Call getNextMethod() to get the current method
            2. If it returns "ITERATION_COMPLETE", call getIterationProgress() and STOP
            3. Otherwise:
               a. Read current test file (readFile)
               b. Generate test methods ONLY for the current method
               c. Append tests using writeFileFromLine
               d. checkSyntax → compileProject → executeTest
               e. getSingleMethodCoverage for this method
               f. completeCurrentMethod with status and coverage
            
            After completing this method, STOP and wait for next instruction.
            """, iteration, targetFile);
    }

    /**
     * 执行单次任务（可能被重试）
     */
    private OrchestrationResult executeTask(UnitTestAssistant assistant, String targetFile, String taskContext) {
        String userMessage = buildUserMessage(targetFile, taskContext);
        
        log.debug("Sending message to LLM: {}", userMessage.substring(0, Math.min(100, userMessage.length())) + "...");
        
        TokenStream tokenStream = assistant.generateTest(userMessage);
        StreamingResponseHandler.StreamingResult streamingResult = streamingHandler.handle(tokenStream);

        if (!streamingResult.isSuccess()) {
            throw new RuntimeException("Streaming failed: " + 
                    (streamingResult.getError() != null ? streamingResult.getError().getMessage() : "Unknown error"));
        }

        return OrchestrationResult.builder()
                .success(true)
                .content(streamingResult.getContent())
                .tokenCount(streamingResult.getTokenCount())
                .durationMs(streamingResult.getDurationMs())
                .build();
    }

    /**
     * 构建 System Prompt
     */
    private String buildSystemPrompt(String projectRoot) {
        // 优先使用动态 Prompt 构建器
        if (dynamicPromptBuilder != null && projectRoot != null) {
            try {
                String dynamicPrompt = dynamicPromptBuilder.buildSystemPrompt(projectRoot);
                log.info("Using dynamically built system prompt");
                return dynamicPrompt;
            } catch (Exception e) {
                log.warn("Failed to build dynamic prompt, falling back to static: {}", e.getMessage());
            }
        }

        // 回退到静态加载
        return loadSystemPrompt();
    }

    /**
     * 构建用户消息
     */
    private String buildUserMessage(String targetFile, String taskContext) {
        StringBuilder message = new StringBuilder();
        
        // 添加修复历史上下文（如果有）
        if (repairTracker != null && repairTracker.shouldContinue()) {
            String repairContext = repairTracker.getRepairContext(null);
            if (!repairContext.contains("No previous repair attempts")) {
                message.append("--- Previous Repair Attempts ---\n");
                message.append(repairContext);
                message.append("\n");
            }
        }
        
        // 添加任务上下文
        if (taskContext != null && !taskContext.isEmpty()) {
            message.append(taskContext).append("\n\n");
        }
        
        // 添加目标文件
        message.append("Target file: ").append(targetFile);
        
        return message.toString();
    }

    /**
     * 创建 AI Assistant
     * 迭代模式使用较小的窗口（6），普通模式使用中等窗口（12）
     * 注意：窗口过大会导致智谱 AI 的 1214 错误（messages 参数非法）
     */
    private UnitTestAssistant createAssistant(String systemPrompt) {
        // 智谱 AI 的 1214 错误问题：
        // LangChain4j 生成的 assistant 消息在有 tool_calls 时会省略 content 字段
        // 智谱 AI 要求 content 字段必须存在
        // 解决方案：在迭代模式下使用最小窗口（1），每次请求都是全新的
        // 这样 ChatMemory 中只有 system 消息，不会包含有问题的 assistant 消息
        boolean iterativeMode = config.getWorkflow() != null && config.getWorkflow().isIterativeMode();
        String llmProtocol = config.getLlm() != null ? config.getLlm().getProtocol() : "";
        
        int memorySize;
        if (iterativeMode && "openai-zhipu".equalsIgnoreCase(llmProtocol)) {
            // 智谱 AI + 迭代模式：使用最小窗口，避免消息格式问题
            memorySize = 1;
            log.info("Using minimal ChatMemory (size=1) for Zhipu AI to avoid 1214 errors");
        } else if (iterativeMode) {
            memorySize = 6;
        } else {
            memorySize = 12;
        }
        log.debug("Creating assistant with ChatMemory window size: {}", memorySize);
        return createAssistant(systemPrompt, memorySize);
    }

    /**
     * 创建 AI Assistant（可指定 memory 大小）
     * @param systemPrompt 系统提示词
     * @param memorySize ChatMemory 窗口大小
     */
    private UnitTestAssistant createAssistant(String systemPrompt, int memorySize) {
        return AiServices.builder(UnitTestAssistant.class)
                .streamingChatModel(streamingLlm)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(memorySize))
                .tools(activeTools)  // 使用当前激活的工具集
                .systemMessageProvider(chatMemoryId -> systemPrompt)
                .build();
    }

    /**
     * 为迭代模式运行单个方法的测试（独立上下文）
     * 每次调用都创建新的 assistant，确保上下文隔离
     * 
     * @param methodContext 方法上下文信息（由 MethodIteratorTool 提供）
     * @param testFilePath 测试文件路径
     * @param projectRoot 项目根目录
     * @return 执行结果
     */
    public OrchestrationResult runSingleMethodTest(String methodContext, String testFilePath, String projectRoot) {
        log.info("Running isolated test generation for method");
        
        // 构建系统提示词
        String systemPrompt = buildSystemPrompt(projectRoot);
        
        // 为单个方法创建新的 assistant（小窗口，确保隔离）
        UnitTestAssistant assistant = createAssistant(systemPrompt, 10);
        
        // 构建简洁的任务消息
        String userMessage = String.format(
            "Generate tests for the following method. This is an ISOLATED task - ignore any previous context.\n\n%s\n\nTest file: %s",
            methodContext, testFilePath
        );
        
        try {
            TokenStream tokenStream = assistant.generateTest(userMessage);
            StreamingResponseHandler.StreamingResult streamingResult = streamingHandler.handle(tokenStream);
            
            if (!streamingResult.isSuccess()) {
                return OrchestrationResult.builder()
                        .success(false)
                        .errorMessage(streamingResult.getError() != null ? 
                                streamingResult.getError().getMessage() : "Streaming failed")
                        .build();
            }
            
            return OrchestrationResult.builder()
                    .success(true)
                    .content(streamingResult.getContent())
                    .tokenCount(streamingResult.getTokenCount())
                    .durationMs(streamingResult.getDurationMs())
                    .build();
        } catch (Exception e) {
            log.error("Failed to run single method test", e);
            return OrchestrationResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 清除当前上下文，准备下一个方法的测试
     * 注意：LangChain4j 的 ChatMemory 是绑定到 Assistant 实例的，
     * 所以"清除"上下文最有效的方式是创建新的 Assistant
     */
    public void clearContextForNextMethod() {
        log.debug("Context will be cleared on next assistant creation");
        // 实际的清除在 createAssistant 时自动发生
    }

    /**
     * 打印注册的工具
     */
    private void logRegisteredTools() {
        log.info("Registered {} tools{}:", activeTools.size(), 
                currentSkill != null ? " (skill: " + currentSkill + ")" : "");
        for (Object tool : activeTools) {
            log.info("  - {}", tool.getClass().getSimpleName());
        }
    }

    /**
     * 切换到指定的 skill，更新激活的工具集
     * @param skillName skill 名称，null 表示使用全部工具
     */
    public void setSkill(String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            this.activeTools = this.allTools;
            this.currentSkill = null;
            log.info("Using all {} tools", allTools.size());
        } else {
            List<Object> filtered = ToolFactory.filterToolsBySkill(allTools, config, skillName);
            if (!filtered.isEmpty()) {
                this.activeTools = filtered;
                this.currentSkill = skillName;
                log.info("Switched to skill '{}' with {} tools", skillName, filtered.size());
            } else {
                log.warn("Skill '{}' not found or empty, keeping current tools", skillName);
            }
        }
    }

    /**
     * 获取当前使用的 skill 名称
     */
    public String getCurrentSkill() {
        return currentSkill;
    }

    /**
     * 获取当前激活的工具数量
     */
    public int getActiveToolCount() {
        return activeTools.size();
    }

    /**
     * 从目标文件路径提取项目根目录
     */
    private String extractProjectRoot(String targetFile) {
        if (targetFile == null) return null;
        
        String normalized = targetFile.replace("\\", "/");
        int srcMainIndex = normalized.indexOf("/src/main/java/");
        if (srcMainIndex > 0) {
            return normalized.substring(0, srcMainIndex);
        }
        
        int srcIndex = normalized.indexOf("/src/");
        if (srcIndex > 0) {
            return normalized.substring(0, srcIndex);
        }
        
        return null;
    }

    /**
     * 提取文件名
     */
    private String extractFileName(String path) {
        if (path == null) return "unknown";
        return Paths.get(path).getFileName().toString();
    }

    /**
     * 加载静态 System Prompt
     */
    private String loadSystemPrompt() {
        String defaultPrompt = """
                You are an expert Java QA Engineer. Your task is to analyze Java code and
                generate JUnit 5 tests.
                Always use the provided tools to read files, write tests, and run them.
                """;

        if (config.getPrompts() != null && config.getPrompts().containsKey("system")) {
            String pathStr = config.getPrompts().get("system");
            log.info("Loading system prompt from: {}", pathStr);
            
            try {
                // 1. Try file system relative to working directory
                Path path = Paths.get(pathStr);
                if (Files.exists(path)) {
                    return Files.readString(path, StandardCharsets.UTF_8);
                }
                
                // 2. Try classpath
                String resourcePath = pathStr.startsWith("/") ? pathStr.substring(1) : pathStr;
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (in != null) {
                        log.info("Loaded system prompt from classpath: {}", resourcePath);
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
                
                log.warn("System prompt file not found at: {}. Using default fallback prompt.", pathStr);
            } catch (IOException e) {
                log.warn("Failed to load system prompt from {}. Using default fallback prompt.", pathStr, e);
            }
        }
        
        log.info("Using default hardcoded system prompt.");
        return defaultPrompt;
    }

    // ==================== Getter 方法（用于测试和扩展）====================

    public RepairTracker getRepairTracker() {
        return repairTracker;
    }

    public void setRepairTracker(RepairTracker repairTracker) {
        this.repairTracker = repairTracker;
    }

    public void setDynamicPromptBuilder(DynamicPromptBuilder dynamicPromptBuilder) {
        this.dynamicPromptBuilder = dynamicPromptBuilder;
    }

    // ==================== 结果类 ====================

    @Data
    @Builder
    public static class OrchestrationResult {
        private boolean success;
        private String content;
        private int tokenCount;
        private long durationMs;
        private String errorMessage;

        public String getSummary() {
            if (success) {
                return String.format("Generated %d chars, %d tokens in %dms",
                        content != null ? content.length() : 0, tokenCount, durationMs);
            } else {
                return "Failed: " + (errorMessage != null ? errorMessage : "Unknown error");
            }
        }
    }

    // ==================== Assistant 接口 ====================

    interface UnitTestAssistant {
        TokenStream generateTest(String targetFilePath);
    }
}
