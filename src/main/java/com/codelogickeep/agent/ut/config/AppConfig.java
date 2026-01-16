package com.codelogickeep.agent.ut.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Map;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BatchConfig {
        @JsonProperty("exclude-patterns")
        private String excludePatterns;

        @JsonProperty("dry-run")
        private boolean dryRun = false;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IncrementalConfig {
        /**
         * 增量模式: uncommitted(默认), staged, compare
         */
        private String mode = "uncommitted";

        /**
         * 基准引用（用于 compare 模式）
         * 不预设任何分支名，完全由用户指定
         */
        @JsonProperty("base-ref")
        private String baseRef;

        /**
         * 目标引用（用于 compare 模式，默认 HEAD）
         */
        @JsonProperty("target-ref")
        private String targetRef = "HEAD";

        /**
         * 排除模式列表（逗号分隔）
         */
        @JsonProperty("exclude-patterns")
        private String excludePatterns;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmConfig {
        @JsonProperty("protocol")
        private String protocol;

        @JsonProperty("provider")
        public void setProvider(String provider) {
            this.protocol = provider;
        }

        @JsonProperty("api-key")
        private String apiKey;

        @JsonProperty("model-name")
        private String modelName;

        private Double temperature;

        @JsonProperty("base-url")
        private String baseUrl;

        private Long timeout; // in seconds

        @JsonProperty("custom-headers")
        private Map<String, String> customHeaders;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkflowConfig {
        @JsonProperty("max-retries")
        private int maxRetries;
        @JsonProperty("coverage-threshold")
        private int coverageThreshold = 80; // 默认 80% 覆盖率阈值
        private boolean interactive = false; // 交互式确认模式
        @JsonProperty("use-lsp")
        private boolean useLsp = false; // 是否使用 LSP 进行语法检查（默认使用 JavaParser）
        // default-skill 已移除：完整单测流程需要所有阶段的工具
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpConfig {
        private List<Map<String, Object>> servers;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillConfig {
        private String name;
        private String description;
        private String type;
        private Map<String, Object> params;
        private List<String> tools; // 工具类名列表，空或 null 表示使用全部工具
    }
}
