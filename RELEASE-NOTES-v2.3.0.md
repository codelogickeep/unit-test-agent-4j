# Release Notes - v2.3.0

**发布日期**: 2026-01-18

## 📋 版本概述

本版本重点改进了**迭代模式**的自动验证管道，修复了大量工具调用和阶段切换相关的问题，提升了系统稳定性和可观测性。

## 🚀 新功能

### 自动验证管道 (Auto-Verification Pipeline)
- **实现** `VerificationPipeline` 类，自动执行语法检查→LSP检查→编译→测试→覆盖率计算
- **减少 LLM 调用**：验证步骤由 Orchestrator 自动执行，无需 LLM 决策
- **智能 LSP 检查跳过**：当 `checkSyntax` 输出包含 "JavaParser + LSP" 时，跳过单独的 LSP 检查

### 动态阶段切换 (Dynamic Phase Switching)
- **完整集成** `runIterative` 方法中的阶段切换逻辑
- **阶段工具集**：
  - ANALYSIS: 分析工具（CodeAnalyzer, Coverage, MethodIterator, Maven）
  - GENERATION: 生成工具（FileSystem, CodeAnalyzer, KnowledgeBase, BoundaryAnalyzer, SyntaxChecker）
  - VERIFICATION: 验证工具（SyntaxChecker, LspSyntaxChecker, Maven, Coverage, TestReport）
  - REPAIR: 修复工具（FileSystem, CodeAnalyzer, TestReport, SyntaxChecker）

### Token 统计与趋势分析
- **流式响应 Token 统计**：`runStream()` 方法现在也记录 Token 消耗
- **统计一致性**：方法级 Token 和总计 Token 数据保持同步

## 🐛 Bug 修复

### 工具调用错误
| 问题 | 修复 |
|------|------|
| `Unknown tool: checkSyntax` | 添加 SyntaxCheckerTool 到 GENERATION 和 REPAIR 阶段 |
| `Unknown tool: compileProject` | 修复阶段切换后从 REPAIR 切回 VERIFICATION |
| `Unknown tool: cleanAndTest` | 添加 MavenExecutorTool 到 ANALYSIS 阶段 |
| `Unknown tool: getMethodCoverageDetails` | 添加 CoverageTool 到 ANALYSIS 阶段 |
| `Missing required parameter: filePath` | 修正工具参数名 (path→filePath, testClass→testClassName) |

### 阶段切换问题
| 问题 | 修复 |
|------|------|
| 初始化时工具未加载 | 添加 `PhaseManager.initializeTools()` 显式加载初始阶段工具 |
| 修复后重试时工具丢失 | 在重试验证前切回 VERIFICATION 阶段 |
| "Retrying verification (attempt 4/3)" | 仅在还有重试机会时才切换阶段 |
| 第一个方法被跳过 | 从 Phase 1 移除 getNextMethod() 调用 |

### 覆盖率计算问题
| 问题 | 修复 |
|------|------|
| 覆盖率始终为 0% | 在 `executeTest` 和 `cleanAndTest` 命令中添加 `jacoco:report` |
| "Error: Unknown tool" 被当作有效数据 | 使用 case-insensitive 错误检测 (`toLowerCase().startsWith("error")`) |

### 配置问题
| 问题 | 修复 |
|------|------|
| 温度显示 0.1 而非 0.3 | 统一所有默认温度值为 0.3 |
| 重试次数硬编码为 3 | 使用配置文件中的 `max-retries` 值 |

### 报告统计问题
| 问题 | 修复 |
|------|------|
| Token 总计与方法详情不一致 | 在 `runLlmAndWait()` 中同时累加到总计 |
| "No token data recorded" | 在 `runStream()` 中添加 Token 统计回调 |

## 🔧 重构

### 配置简化
- **移除** `skills` 和 `mcp` 配置（未使用）
- **移除** `enable-phase-switching` 选项（自动与 `iterative-mode` 绑定）
- **移除** 相关代码：`ToolFactory.filterToolsBySkill()`, `getAvailableSkillNames()` 等

### 代码改进
- 阶段切换日志从 `debug` 改为 `info` 级别
- 添加 `truncateForLog()` 辅助方法
- 添加管道每步的输入/输出日志
- 添加 `VerificationResult.details` 字段存储成功时的工具输出

## 📊 变更统计

| 类型 | 数量 |
|------|------|
| 新功能 (feat) | 2 |
| Bug 修复 (fix) | 16 |
| 重构 (refactor) | 3 |
| 测试 (test) | 1 |
| **总计** | **22** |

## 📁 主要文件变更

### 核心文件
- `SimpleAgentOrchestrator.java` - 迭代模式主逻辑，阶段切换集成
- `VerificationPipeline.java` - 自动验证管道实现
- `PhaseManager.java` - 阶段管理和工具加载
- `WorkflowPhase.java` - 各阶段工具集定义
- `AgentExecutor.java` - 流式响应 Token 统计

### 工具文件
- `MavenExecutorTool.java` - 添加 `jacoco:report` 目标
- `CoverageAnalyzer.java` - case-insensitive 错误检测
- `ToolFactory.java` - 移除 skill 过滤逻辑

### 配置文件
- `agent.yml` - 移除 skills/mcp，更新默认值
- `AppConfig.java` - 移除对应配置类
- `App.java` - 统一默认温度值

## ⬆️ 升级指南

1. **配置迁移**：如果使用了 `skills` 或 `mcp` 配置，请删除（已废弃）
2. **配置检查**：确认 `~/.utagent/agent.yml` 中的 `temperature` 设置
3. **重试配置**：现在会正确使用 `max-retries` 配置值

## 🔜 下一步计划

- [ ] 支持更多 LLM 协议
- [ ] 优化 Token 使用效率
- [ ] 添加测试质量评分

---
*Generated on 2026-01-18*
