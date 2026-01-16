package com.codelogickeep.agent.ut.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AppConfig 单元测试
 * 测试配置文件解析、兼容性和默认值
 */
@DisplayName("AppConfig Tests")
class AppConfigTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper(new YAMLFactory());
    }

    @Nested
    @DisplayName("Basic Parsing")
    class BasicParsing {

        @Test
        @DisplayName("should parse empty YAML to default config")
        void shouldParseEmptyYaml() throws Exception {
            String yaml = "{}";
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertNotNull(config);
            assertNull(config.getLlm());
            assertNull(config.getWorkflow());
        }

        @Test
        @DisplayName("should parse LLM config")
        void shouldParseLlmConfig() throws Exception {
            String yaml = """
                llm:
                  protocol: openai
                  api-key: test-key
                  model-name: gpt-4
                  base-url: https://api.openai.com
                  temperature: 0.7
                  timeout: 60
                """;
            
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertNotNull(config.getLlm());
            assertEquals("openai", config.getLlm().getProtocol());
            assertEquals("test-key", config.getLlm().getApiKey());
            assertEquals("gpt-4", config.getLlm().getModelName());
            assertEquals("https://api.openai.com", config.getLlm().getBaseUrl());
            assertEquals(0.7, config.getLlm().getTemperature());
            assertEquals(60L, config.getLlm().getTimeout());
        }

        @Test
        @DisplayName("should parse workflow config with defaults")
        void shouldParseWorkflowConfig() throws Exception {
            String yaml = """
                workflow:
                  max-retries: 5
                  use-lsp: true
                  iterative-mode: true
                """;
            
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertNotNull(config.getWorkflow());
            assertEquals(5, config.getWorkflow().getMaxRetries());
            assertTrue(config.getWorkflow().isUseLsp());
            assertTrue(config.getWorkflow().isIterativeMode());
            // 检查默认值
            assertEquals(80, config.getWorkflow().getCoverageThreshold());
            assertEquals(80, config.getWorkflow().getMethodCoverageThreshold());
            assertEquals(3, config.getWorkflow().getMaxStaleIterations());
            assertEquals(1, config.getWorkflow().getMinCoverageGain());
        }

        @Test
        @DisplayName("should parse incremental config")
        void shouldParseIncrementalConfig() throws Exception {
            String yaml = """
                incremental:
                  mode: compare
                  base-ref: main
                  target-ref: feature-branch
                  exclude-patterns: "**/test/**,**/generated/**"
                """;
            
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertNotNull(config.getIncremental());
            assertEquals("compare", config.getIncremental().getMode());
            assertEquals("main", config.getIncremental().getBaseRef());
            assertEquals("feature-branch", config.getIncremental().getTargetRef());
            assertEquals("**/test/**,**/generated/**", config.getIncremental().getExcludePatterns());
        }

        @Test
        @DisplayName("should parse batch config")
        void shouldParseBatchConfig() throws Exception {
            String yaml = """
                batch:
                  exclude-patterns: "**/test/**"
                  dry-run: true
                """;
            
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertNotNull(config.getBatch());
            assertEquals("**/test/**", config.getBatch().getExcludePatterns());
            assertTrue(config.getBatch().isDryRun());
        }
    }

    @Nested
    @DisplayName("Backward Compatibility")
    class BackwardCompatibility {

        @Test
        @DisplayName("should ignore unknown fields in root config")
        void shouldIgnoreUnknownFieldsInRoot() throws Exception {
            String yaml = """
                llm:
                  protocol: openai
                unknownField: someValue
                anotherUnknown:
                  nested: value
                """;
            
            // 不应该抛出异常
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertNotNull(config);
            assertEquals("openai", config.getLlm().getProtocol());
        }

        @Test
        @DisplayName("should ignore unknown fields in LlmConfig")
        void shouldIgnoreUnknownFieldsInLlmConfig() throws Exception {
            String yaml = """
                llm:
                  protocol: openai
                  api-key: test-key
                  unknownLlmField: ignored
                  futureFeature: true
                """;
            
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertNotNull(config.getLlm());
            assertEquals("openai", config.getLlm().getProtocol());
            assertEquals("test-key", config.getLlm().getApiKey());
        }

        @Test
        @DisplayName("should ignore unknown fields in WorkflowConfig")
        void shouldIgnoreUnknownFieldsInWorkflowConfig() throws Exception {
            String yaml = """
                workflow:
                  max-retries: 3
                  deprecatedField: old-value
                  legacyOption: true
                """;
            
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertNotNull(config.getWorkflow());
            assertEquals(3, config.getWorkflow().getMaxRetries());
        }

        @Test
        @DisplayName("should support provider as alias for protocol")
        void shouldSupportProviderAsAliasForProtocol() throws Exception {
            String yaml = """
                llm:
                  provider: anthropic
                  apiKey: test-key
                """;
            
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertNotNull(config.getLlm());
            // provider 应该被映射到 protocol
            assertEquals("anthropic", config.getLlm().getProtocol());
        }
    }

    @Nested
    @DisplayName("Default Values")
    class DefaultValues {

        @Test
        @DisplayName("WorkflowConfig should have correct defaults")
        void workflowConfigShouldHaveCorrectDefaults() {
            AppConfig.WorkflowConfig workflow = new AppConfig.WorkflowConfig();
            
            assertEquals(80, workflow.getCoverageThreshold());
            assertFalse(workflow.isInteractive());
            assertFalse(workflow.isUseLsp());
            assertFalse(workflow.isIterativeMode());
            assertEquals(80, workflow.getMethodCoverageThreshold());
            assertFalse(workflow.isSkipLowPriority());
            assertEquals(3, workflow.getMaxStaleIterations());
            assertEquals(1, workflow.getMinCoverageGain());
        }

        @Test
        @DisplayName("IncrementalConfig should have correct defaults")
        void incrementalConfigShouldHaveCorrectDefaults() {
            AppConfig.IncrementalConfig incremental = new AppConfig.IncrementalConfig();
            
            assertEquals("uncommitted", incremental.getMode());
            assertNull(incremental.getBaseRef());
            assertEquals("HEAD", incremental.getTargetRef());
            assertNull(incremental.getExcludePatterns());
        }

        @Test
        @DisplayName("BatchConfig should have correct defaults")
        void batchConfigShouldHaveCorrectDefaults() {
            AppConfig.BatchConfig batch = new AppConfig.BatchConfig();
            
            assertNull(batch.getExcludePatterns());
            assertFalse(batch.isDryRun());
        }
    }

    @Nested
    @DisplayName("Complex Scenarios")
    class ComplexScenarios {

        @Test
        @DisplayName("should parse full config with all sections")
        void shouldParseFullConfig() throws Exception {
            String yaml = """
                llm:
                  protocol: openai-zhipu
                  api-key: sk-xxx
                  model-name: glm-4
                  base-url: https://open.bigmodel.cn/api/coding/paas/v4
                  temperature: 0.7
                  timeout: 120
                
                workflow:
                  max-retries: 5
                  coverage-threshold: 70
                  use-lsp: true
                  iterative-mode: true
                  method-coverage-threshold: 75
                  skip-low-priority: true
                  max-stale-iterations: 5
                  min-coverage-gain: 2
                
                incremental:
                  mode: compare
                  base-ref: main
                  target-ref: HEAD
                
                batch:
                  exclude-patterns: "**/test/**"
                  dry-run: false
                """;
            
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            // LLM
            assertNotNull(config.getLlm());
            assertEquals("openai-zhipu", config.getLlm().getProtocol());
            assertEquals("glm-4", config.getLlm().getModelName());
            
            // Workflow
            assertNotNull(config.getWorkflow());
            assertEquals(5, config.getWorkflow().getMaxRetries());
            assertTrue(config.getWorkflow().isUseLsp());
            assertTrue(config.getWorkflow().isIterativeMode());
            assertEquals(5, config.getWorkflow().getMaxStaleIterations());
            
            // Incremental
            assertNotNull(config.getIncremental());
            assertEquals("compare", config.getIncremental().getMode());
            
            // Batch
            assertNotNull(config.getBatch());
            assertFalse(config.getBatch().isDryRun());
        }

        @Test
        @DisplayName("should handle mixed known and unknown fields")
        void shouldHandleMixedKnownAndUnknownFields() throws Exception {
            String yaml = """
                llm:
                  protocol: openai
                  api-key: test
                  futureField1: ignored
                workflow:
                  max-retries: 3
                  futureField2: also-ignored
                unknownTopLevel: completely-ignored
                """;
            
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertEquals("openai", config.getLlm().getProtocol());
            assertEquals("test", config.getLlm().getApiKey());
            assertEquals(3, config.getWorkflow().getMaxRetries());
        }

        @Test
        @DisplayName("should handle null values gracefully")
        void shouldHandleNullValuesGracefully() throws Exception {
            String yaml = """
                llm:
                  protocol: openai
                  api-key: ~
                  model-name: null
                """;
            
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertNotNull(config.getLlm());
            assertEquals("openai", config.getLlm().getProtocol());
            assertNull(config.getLlm().getApiKey());
            assertNull(config.getLlm().getModelName());
        }
    }

    @Nested
    @DisplayName("Skills and MCP Config")
    class SkillsAndMcpConfig {

        @Test
        @DisplayName("should parse skills config")
        void shouldParseSkillsConfig() throws Exception {
            String yaml = """
                skills:
                  - name: analysis
                    description: Code analysis tools
                    tools:
                      - CodeAnalyzerTool
                      - CoverageTool
                  - name: generation
                    description: Test generation tools
                    tools:
                      - FileSystemTool
                """;
            
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertNotNull(config.getSkills());
            assertEquals(2, config.getSkills().size());
            assertEquals("analysis", config.getSkills().get(0).getName());
            assertEquals(2, config.getSkills().get(0).getTools().size());
        }

        @Test
        @DisplayName("should parse MCP config")
        void shouldParseMcpConfig() throws Exception {
            String yaml = """
                mcp:
                  servers:
                    - name: local-server
                      command: node
                      args:
                        - server.js
                """;
            
            AppConfig config = mapper.readValue(yaml, AppConfig.class);
            
            assertNotNull(config.getMcp());
            assertNotNull(config.getMcp().getServers());
            assertEquals(1, config.getMcp().getServers().size());
        }
    }
}
