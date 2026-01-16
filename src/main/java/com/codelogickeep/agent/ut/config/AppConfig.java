package com.codelogickeep.agent.ut.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Map;
import java.util.List;

@Data
public class AppConfig {
    private LlmConfig llm;
    private WorkflowConfig workflow;
    private Map<String, String> prompts; // Key: prompt name (e.g. "system"), Value: file path
    private McpConfig mcp;
    private List<SkillConfig> skills;
    private Map<String, String> dependencies; // New: key is artifactId, value is min version
    private BatchConfig batch; // New: batch mode settings
    private IncrementalConfig incremental; // New: incremental mode settings

    @Data
    public static class BatchConfig {
        private String excludePatterns;
        private boolean dryRun = false;
    }

    @Data
    public static class IncrementalConfig {
        /**
         * 增量模式: uncommitted(默认), staged, compare
         */
        private String mode = "uncommitted";

        /**
         * 基准引用（用于 compare 模式）
         * 不预设任何分支名，完全由用户指定
         */
        private String baseRef;

        /**
         * 目标引用（用于 compare 模式，默认 HEAD）
         */
        private String targetRef = "HEAD";

        /**
         * 排除模式列表（逗号分隔）
         */
        private String excludePatterns;
    }

    @Data
    public static class LlmConfig {
        @JsonProperty("protocol")
        private String protocol;

        @JsonProperty("provider")
        public void setProvider(String provider) {
            this.protocol = provider;
        }

        private String apiKey;
        private String modelName;
        private Double temperature;
        private String baseUrl;
        private Long timeout; // in seconds
        private Map<String, String> customHeaders;
    }

    @Data
    public static class WorkflowConfig {
        private int maxRetries;
        private int coverageThreshold = 80; // 默认 80% 覆盖率阈值
        private boolean interactive = false; // 交互式确认模式
        @JsonProperty("use-lsp")
        private boolean useLsp = false; // 是否使用 LSP 进行语法检查（默认使用 JavaParser）
        @JsonProperty("default-skill")
        private String defaultSkill; // 默认使用的 skill 名称，null 表示使用全部工具
        @JsonProperty("iterative-mode")
        private boolean iterativeMode = false; // 是否启用逐函数迭代模式
        @JsonProperty("method-coverage-threshold")
        private int methodCoverageThreshold = 80; // 单个方法覆盖率阈值
        @JsonProperty("skip-low-priority")
        private boolean skipLowPriority = false; // 是否跳过低优先级方法 (P2)
        @JsonProperty("max-stale-iterations")
        private int maxStaleIterations = 3; // 最大无进展迭代次数
        @JsonProperty("min-coverage-gain")
        private int minCoverageGain = 1; // 每次迭代最小覆盖率提升 (%)
    }

    @Data
    public static class McpConfig {
        private List<Map<String, Object>> servers;
    }

    @Data
    public static class SkillConfig {
        private String name;
        private String description;
        private String type;
        private Map<String, Object> params;
        private List<String> tools; // 工具类名列表，空或 null 表示使用全部工具
    }
}
