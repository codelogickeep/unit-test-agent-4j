# 自研轻量级 Agent 框架设计文档

> 目标：替换 LangChain4j，实现一套针对单测 Agent 优化的轻量级框架

## 1. 背景与动机

### 1.1 当前问题

| 问题 | 影响 | 根因 |
|------|------|------|
| 智谱 AI 1214 错误 | Agent 执行失败 | LangChain4j 生成的 assistant 消息在有 tool_calls 时省略 content 字段 |
| 上下文隔离困难 | 迭代模式不可靠 | ChatMemory 绑定到 Assistant，需要重建实例才能清除 |
| 包体积过大 | 50MB+ | 引入大量未使用的依赖 |
| 调试困难 | 排错耗时 | HTTP 交互不透明 |

### 1.2 目标

- ✅ 完全控制消息格式，兼容所有 LLM 提供商
- ✅ 精确的上下文管理，支持按需清除/保留
- ✅ 轻量级实现，减少依赖
- ✅ 保留现有 `@Tool` 注解，最小化工具代码改动
- ✅ 透明的请求/响应日志

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        SimpleAgentFramework                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────────┐    ┌──────────────────┐    ┌────────────────┐ │
│  │   LlmAdapter     │    │   ToolRegistry   │    │ ContextManager │ │
│  │   ────────────   │    │   ────────────   │    │ ────────────── │ │
│  │ • OpenAiAdapter  │    │ • scanTools()    │    │ • messages     │ │
│  │ • ClaudeAdapter  │    │ • toJsonSchema() │    │ • addMessage() │ │
│  │ • ZhipuAdapter   │    │ • invoke()       │    │ • clear()      │ │
│  │ • GeminiAdapter  │    │                  │    │ • getTokens()  │ │
│  └────────┬─────────┘    └────────┬─────────┘    └───────┬────────┘ │
│           │                       │                       │          │
│           └───────────────────────┼───────────────────────┘          │
│                                   ▼                                  │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │                       AgentExecutor                              ││
│  │  ─────────────────────────────────────────────────────────────  ││
│  │                                                                  ││
│  │   ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐     ││
│  │   │  User   │───▶│   LLM   │───▶│  Tool   │───▶│ Result  │─┐   ││
│  │   │ Message │    │  Call   │    │ Execute │    │         │ │   ││
│  │   └─────────┘    └─────────┘    └─────────┘    └─────────┘ │   ││
│  │        ▲                                                    │   ││
│  │        └────────────────────────────────────────────────────┘   ││
│  │                         (ReAct Loop)                             ││
│  └─────────────────────────────────────────────────────────────────┘│
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │                    StreamingHandler                              ││
│  │  • onToken(String token)                                        ││
│  │  • onToolCall(ToolCall call)                                    ││
│  │  • onComplete()                                                 ││
│  │  • onError(Throwable e)                                         ││
│  └─────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 核心类设计

#### 2.2.1 消息模型

```java
// 统一消息模型
public sealed interface Message permits SystemMessage, UserMessage, AssistantMessage, ToolMessage {
    String role();
    String content();
}

public record SystemMessage(String content) implements Message {
    @Override public String role() { return "system"; }
}

public record UserMessage(String content) implements Message {
    @Override public String role() { return "user"; }
}

public record AssistantMessage(
    String content,           // 始终存在，解决智谱 1214 问题
    List<ToolCall> toolCalls  // 可选
) implements Message {
    @Override public String role() { return "assistant"; }
}

public record ToolMessage(
    String toolCallId,
    String name,
    String content
) implements Message {
    @Override public String role() { return "tool"; }
}

public record ToolCall(
    String id,
    String name,
    Map<String, Object> arguments
) {}
```

#### 2.2.2 LLM 适配器接口

```java
public interface LlmAdapter {
    /**
     * 发送聊天请求（同步）
     */
    AssistantMessage chat(List<Message> messages, List<ToolDefinition> tools);
    
    /**
     * 发送聊天请求（流式）
     */
    void chatStream(List<Message> messages, List<ToolDefinition> tools, StreamingHandler handler);
    
    /**
     * 获取适配器名称
     */
    String getName();
}

// 各提供商实现
public class OpenAiAdapter implements LlmAdapter { ... }
public class ClaudeAdapter implements LlmAdapter { ... }
public class ZhipuAdapter implements LlmAdapter { ... }  // 重点：正确处理消息格式
public class GeminiAdapter implements LlmAdapter { ... }
```

#### 2.2.3 工具注册表

```java
public class ToolRegistry {
    private final Map<String, ToolExecutor> tools = new HashMap<>();
    private final List<ToolDefinition> definitions = new ArrayList<>();
    
    /**
     * 扫描对象的 @Tool 方法并注册
     */
    public void register(Object toolInstance) {
        for (Method method : toolInstance.getClass().getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null) {
                String name = method.getName();
                String description = annotation.value();
                
                // 生成参数 JSON Schema
                JsonSchema parameters = generateSchema(method);
                
                tools.put(name, new ToolExecutor(toolInstance, method));
                definitions.add(new ToolDefinition(name, description, parameters));
            }
        }
    }
    
    /**
     * 执行工具调用
     */
    public String invoke(String name, Map<String, Object> arguments) {
        ToolExecutor executor = tools.get(name);
        if (executor == null) {
            return "Error: Unknown tool: " + name;
        }
        return executor.execute(arguments);
    }
    
    public List<ToolDefinition> getDefinitions() {
        return Collections.unmodifiableList(definitions);
    }
}
```

#### 2.2.4 上下文管理器

```java
public class ContextManager {
    private final List<Message> messages = new ArrayList<>();
    private final int maxMessages;
    private int totalTokens = 0;
    
    public ContextManager(int maxMessages) {
        this.maxMessages = maxMessages;
    }
    
    public void setSystemMessage(String content) {
        // System 消息始终在首位
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage) {
            messages.set(0, new SystemMessage(content));
        } else {
            messages.add(0, new SystemMessage(content));
        }
    }
    
    public void addMessage(Message message) {
        messages.add(message);
        trimIfNeeded();
    }
    
    public void clear() {
        // 保留 system 消息
        Message system = messages.isEmpty() ? null : messages.get(0);
        messages.clear();
        if (system instanceof SystemMessage) {
            messages.add(system);
        }
    }
    
    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }
    
    private void trimIfNeeded() {
        // 保留 system + 最新的 N-1 条消息
        while (messages.size() > maxMessages) {
            if (messages.size() > 1 && !(messages.get(1) instanceof SystemMessage)) {
                messages.remove(1);
            } else {
                break;
            }
        }
    }
}
```

#### 2.2.5 Agent 执行器

```java
public class AgentExecutor {
    private final LlmAdapter llmAdapter;
    private final ToolRegistry toolRegistry;
    private final ContextManager contextManager;
    private final int maxIterations;
    
    public AgentExecutor(LlmAdapter llmAdapter, ToolRegistry toolRegistry, 
                         ContextManager contextManager, int maxIterations) {
        this.llmAdapter = llmAdapter;
        this.toolRegistry = toolRegistry;
        this.contextManager = contextManager;
        this.maxIterations = maxIterations;
    }
    
    /**
     * 执行 Agent 任务（ReAct 循环）
     */
    public AgentResult run(String userMessage) {
        contextManager.addMessage(new UserMessage(userMessage));
        
        int iteration = 0;
        while (iteration < maxIterations) {
            iteration++;
            
            // 1. 调用 LLM
            AssistantMessage response = llmAdapter.chat(
                contextManager.getMessages(),
                toolRegistry.getDefinitions()
            );
            contextManager.addMessage(response);
            
            // 2. 检查是否需要调用工具
            if (response.toolCalls() == null || response.toolCalls().isEmpty()) {
                // 没有工具调用，任务完成
                return AgentResult.success(response.content());
            }
            
            // 3. 执行工具调用
            for (ToolCall toolCall : response.toolCalls()) {
                String result = toolRegistry.invoke(toolCall.name(), toolCall.arguments());
                contextManager.addMessage(new ToolMessage(
                    toolCall.id(),
                    toolCall.name(),
                    result
                ));
            }
        }
        
        return AgentResult.maxIterationsReached(iteration);
    }
    
    /**
     * 流式执行
     */
    public void runStream(String userMessage, AgentStreamHandler handler) {
        // 类似逻辑，但使用流式回调
    }
}
```

---

## 3. 实现计划

### Phase 1: 核心框架（3天）

| 任务 | 时间 | 输出 |
|------|------|------|
| 1.1 消息模型 | 0.5天 | `Message`, `ToolCall` 等 record 类 |
| 1.2 工具注册表 | 1天 | `ToolRegistry`, 保持 `@Tool` 注解兼容 |
| 1.3 上下文管理 | 0.5天 | `ContextManager` |
| 1.4 Agent 执行器 | 1天 | `AgentExecutor` 基础实现 |

### Phase 2: LLM 适配器（2天）

| 任务 | 时间 | 输出 |
|------|------|------|
| 2.1 OpenAI 适配器 | 0.5天 | `OpenAiAdapter`（含智谱兼容） |
| 2.2 Claude 适配器 | 0.5天 | `ClaudeAdapter` |
| 2.3 Gemini 适配器 | 0.5天 | `GeminiAdapter` |
| 2.4 智谱专用适配器 | 0.5天 | `ZhipuAdapter`（解决 1214 问题） |

### Phase 3: 流式处理（1天）

| 任务 | 时间 | 输出 |
|------|------|------|
| 3.1 流式处理接口 | 0.5天 | `StreamingHandler` |
| 3.2 SSE 解析器 | 0.5天 | 解析各 LLM 的 SSE 格式 |

### Phase 4: 迁移与集成（2天）

| 任务 | 时间 | 输出 |
|------|------|------|
| 4.1 替换 AgentOrchestrator | 1天 | 使用新框架替换 LangChain4j |
| 4.2 迁移现有工具 | 0.5天 | 保持 `@Tool` 注解，无需改动 |
| 4.3 测试验证 | 0.5天 | 运行完整测试 |

### Phase 5: 清理与优化（1天）

| 任务 | 时间 | 输出 |
|------|------|------|
| 5.1 移除 LangChain4j 依赖 | 0.5天 | 更新 pom.xml |
| 5.2 包体积优化 | 0.5天 | 验证 JAR 大小减少 |

---

## 4. 包结构

```
com.codelogickeep.agent.ut
├── framework/                    # 新增：自研框架
│   ├── model/
│   │   ├── Message.java         # sealed interface
│   │   ├── SystemMessage.java
│   │   ├── UserMessage.java
│   │   ├── AssistantMessage.java
│   │   ├── ToolMessage.java
│   │   ├── ToolCall.java
│   │   └── ToolDefinition.java
│   ├── adapter/
│   │   ├── LlmAdapter.java      # interface
│   │   ├── OpenAiAdapter.java
│   │   ├── ClaudeAdapter.java
│   │   ├── ZhipuAdapter.java
│   │   └── GeminiAdapter.java
│   ├── tool/
│   │   ├── ToolRegistry.java
│   │   ├── ToolExecutor.java
│   │   └── JsonSchemaGenerator.java
│   ├── context/
│   │   └── ContextManager.java
│   ├── executor/
│   │   ├── AgentExecutor.java
│   │   ├── AgentResult.java
│   │   └── StreamingHandler.java
│   └── util/
│       ├── HttpClientUtil.java
│       └── JsonUtil.java
├── engine/                       # 现有：简化
│   ├── AgentOrchestrator.java   # 重构：使用新框架
│   ├── DynamicPromptBuilder.java
│   ├── RepairTracker.java
│   └── RetryExecutor.java
├── tools/                        # 现有：保持不变
│   ├── FileSystemTool.java
│   ├── CodeAnalyzerTool.java
│   └── ...
└── config/
    └── AppConfig.java
```

---

## 5. 关键设计决策

### 5.1 保留 `@Tool` 注解

**决策**：复用 LangChain4j 的 `@Tool` 和 `@P` 注解定义

**原因**：
- 现有 28+ 个工具方法无需修改
- 注解定义简单，可以直接复用或复制定义

**实现**：
```java
// 如果不想依赖 LangChain4j，可以定义自己的注解
package com.codelogickeep.agent.ut.framework.annotation;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {
    String value();  // 工具描述
}

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface P {
    String value();  // 参数描述
}
```

### 5.2 智谱 AI 消息格式处理

**问题**：智谱 AI 要求 assistant 消息必须有 `content` 字段

**解决**：
```java
// ZhipuAdapter.java
private JsonObject buildAssistantMessage(AssistantMessage msg) {
    JsonObject obj = new JsonObject();
    obj.addProperty("role", "assistant");
    // 关键：始终包含 content，即使为空
    obj.addProperty("content", msg.content() != null ? msg.content() : "");
    
    if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
        obj.add("tool_calls", buildToolCalls(msg.toolCalls()));
    }
    return obj;
}
```

### 5.3 流式处理

**设计**：使用回调接口，不依赖 RxJava 等响应式库

```java
public interface StreamingHandler {
    void onToken(String token);           // 文本 token
    void onToolCall(ToolCall call);       // 工具调用
    void onComplete(String fullContent);  // 完成
    void onError(Throwable error);        // 错误
}
```

---

## 6. 依赖变化

### 移除
```xml
<!-- 移除 LangChain4j 相关依赖 -->
- dev.langchain4j:langchain4j
- dev.langchain4j:langchain4j-open-ai
- dev.langchain4j:langchain4j-anthropic
- dev.langchain4j:langchain4j-google-ai-gemini
- dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2
```

### 保留
```xml
<!-- 保留现有依赖 -->
+ com.fasterxml.jackson.core:jackson-databind  <!-- JSON 处理 -->
+ JDK 21 HttpClient                            <!-- HTTP 请求 -->
```

### 预期效果
- JAR 包大小：~50MB → ~15MB（预计减少 70%）
- 启动时间：减少（更少的类加载）
- 内存占用：减少

---

## 7. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| JSON Schema 生成复杂 | 工具参数解析失败 | 参考 LangChain4j 实现，充分测试 |
| SSE 解析差异 | 流式响应异常 | 各 LLM 单独适配，增加错误处理 |
| 工具调用并行执行 | 性能下降 | 先串行实现，后续优化 |

---

## 8. 验收标准

- [ ] 所有现有工具正常工作
- [ ] 智谱 AI 无 1214 错误
- [ ] 迭代模式上下文正确隔离
- [ ] 流式响应正常显示
- [ ] JAR 包大小 < 20MB
- [ ] 测试覆盖率 > 60%

---

## 9. 时间线

```
Day 1-3: Phase 1 - 核心框架
Day 4-5: Phase 2 - LLM 适配器
Day 6:   Phase 3 - 流式处理
Day 7-8: Phase 4 - 迁移集成
Day 9:   Phase 5 - 清理优化
───────────────────────────────
Total: 9 工作日
```

---

*文档版本: 1.0*
*创建日期: 2026-01-15*
*作者: AI Assistant*
