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
    }
}
