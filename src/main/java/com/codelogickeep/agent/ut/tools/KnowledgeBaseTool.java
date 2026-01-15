package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.annotation.P;
import com.codelogickeep.agent.ut.framework.annotation.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 知识库工具（简化版）
 * 
 * 注：LangChain4j 已移除，embedding 功能暂时禁用。
 * 当前使用基于关键词的简单搜索作为替代。
 */
public class KnowledgeBaseTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTool.class);

    // Supported file extensions for knowledge base
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".java", ".md", ".txt", ".xml", ".yml", ".yaml", ".json"
    );

    private boolean isInitialized = false;
    private int documentCount = 0;
    private String indexedPath = null;
    private List<DocumentInfo> documents = new ArrayList<>();

    /**
     * 文档信息（简化版）
     */
    private static class DocumentInfo {
        String path;
        String content;
        String type;
        String fileName;
        
        DocumentInfo(String path, String content, String type, String fileName) {
            this.path = path;
            this.content = content;
            this.type = type;
            this.fileName = fileName;
        }
    }

    public void init(AppConfig config, String knowledgeBasePath) {
        if (knowledgeBasePath == null || knowledgeBasePath.isEmpty()) {
            log.warn("No knowledge base path provided. Knowledge base tool will use fallback logic.");
            return;
        }

        try {
            Path path = Paths.get(knowledgeBasePath);
            if (!Files.exists(path)) {
                log.error("Knowledge base path does not exist: {}", knowledgeBasePath);
                return;
            }

            log.info("Initializing knowledge base from: {}", knowledgeBasePath);

            documents = new ArrayList<>();
            
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.find(path, 10,
                        (p, attr) -> attr.isRegularFile() && isSupportedFile(p.toString()))) {

                    List<Path> files = stream.collect(Collectors.toList());
                    if (files.isEmpty()) {
                        log.warn("No supported files found in knowledge base directory: {}", knowledgeBasePath);
                    }

                    for (Path p : files) {
                        try {
                            String content = Files.readString(p);
                            String type = getFileType(p.toString());
                            documents.add(new DocumentInfo(p.toString(), content, type, p.getFileName().toString()));
                            log.debug("Loaded document: {} ({})", p.getFileName(), type);
                        } catch (Exception e) {
                            log.warn("Failed to load document: {}", p, e);
                        }
                    }
                }
            } else {
                String content = Files.readString(path);
                String type = getFileType(path.toString());
                documents.add(new DocumentInfo(path.toString(), content, type, path.getFileName().toString()));
            }

            if (documents.isEmpty()) {
                log.warn("No valid documents found in {}", knowledgeBasePath);
                return;
            }

            this.isInitialized = true;
            this.documentCount = documents.size();
            this.indexedPath = knowledgeBasePath;
            
            long javaCount = documents.stream().filter(d -> "java".equals(d.type)).count();
            long mdCount = documents.stream().filter(d -> "markdown".equals(d.type)).count();
            long otherCount = documentCount - javaCount - mdCount;
            
            log.info("Knowledge base initialized with {} documents (Java: {}, Markdown: {}, Other: {})",
                    documentCount, javaCount, mdCount, otherCount);

        } catch (Exception e) {
            log.error("Failed to initialize knowledge base", e);
        }
    }

    private boolean isSupportedFile(String path) {
        String lowerPath = path.toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lowerPath::endsWith);
    }

    private String getFileType(String path) {
        String lowerPath = path.toLowerCase();
        if (lowerPath.endsWith(".java")) return "java";
        if (lowerPath.endsWith(".md")) return "markdown";
        if (lowerPath.endsWith(".xml")) return "xml";
        if (lowerPath.endsWith(".yml") || lowerPath.endsWith(".yaml")) return "yaml";
        if (lowerPath.endsWith(".json")) return "json";
        return "other";
    }

    @Tool("Get knowledge base status and statistics")
    public String getKnowledgeBaseStatus() {
        log.info("Tool Input - getKnowledgeBaseStatus");
        StringBuilder sb = new StringBuilder();
        sb.append("=== Knowledge Base Status ===\n");
        sb.append("Initialized: ").append(isInitialized).append("\n");
        if (isInitialized) {
            sb.append("Indexed Path: ").append(indexedPath).append("\n");
            sb.append("Document Count: ").append(documentCount).append("\n");
            sb.append("Supported Types: ").append(String.join(", ", SUPPORTED_EXTENSIONS)).append("\n");
            sb.append("Note: Using keyword-based search (embedding disabled)\n");
        } else {
            sb.append("Status: Not initialized. Use -kb <path> to specify a knowledge base.\n");
        }
        sb.append("=============================\n");
        String result = sb.toString();
        log.info("Tool Output - getKnowledgeBaseStatus: initialized={}", isInitialized);
        return result;
    }

    @Tool("Search for information in the knowledge base using keywords")
    public String searchKnowledge(
            @P("Search query or keywords") String query,
            @P("Optional: filter by document type (java/markdown/xml/yaml/all). Default: all") String filterType
    ) {
        log.info("Tool Input - searchKnowledge: query={}, filterType={}", query, filterType);
        if (!isInitialized) {
            return fallbackSearch(query);
        }

        try {
            String[] keywords = query.toLowerCase().split("\\s+");
            StringBuilder result = new StringBuilder();
            result.append("Knowledge Base Results for: ").append(query).append("\n");
            if (filterType != null && !"all".equalsIgnoreCase(filterType)) {
                result.append("(Filtered by type: ").append(filterType).append(")\n");
            }
            result.append("\n");

            boolean found = false;
            for (DocumentInfo doc : documents) {
                // 类型过滤
                if (filterType != null && !"all".equalsIgnoreCase(filterType) && !filterType.equalsIgnoreCase(doc.type)) {
                    continue;
                }
                
                // 关键词匹配
                String lowerContent = doc.content.toLowerCase();
                int matchCount = 0;
                for (String keyword : keywords) {
                    if (lowerContent.contains(keyword)) {
                        matchCount++;
                    }
                }
                
                // 如果至少一半的关键词匹配
                if (matchCount >= Math.max(1, keywords.length / 2)) {
                    found = true;
                    result.append("--- Source: ").append(doc.fileName);
                    result.append(" | Type: ").append(doc.type);
                    result.append(" | Match: ").append(matchCount).append("/").append(keywords.length).append(" ---\n");
                    
                    // 提取包含关键词的相关片段
                    String snippet = extractRelevantSnippet(doc.content, keywords);
                    result.append(snippet).append("\n\n");
                }
            }

            if (!found) {
                String noResultsMsg = "No relevant information found in knowledge base for: " + query;
                log.info("Tool Output - searchKnowledge: {}", noResultsMsg);
                return noResultsMsg;
            }

            String finalResult = result.toString();
            log.info("Tool Output - searchKnowledge: length={}", finalResult.length());
            return finalResult;

        } catch (Exception e) {
            log.error("Error searching knowledge base", e);
            return "Error searching knowledge base: " + e.getMessage();
        }
    }

    /**
     * 提取包含关键词的相关片段
     */
    private String extractRelevantSnippet(String content, String[] keywords) {
        String[] lines = content.split("\n");
        StringBuilder snippet = new StringBuilder();
        int maxSnippetLines = 15;
        int addedLines = 0;
        
        for (int i = 0; i < lines.length && addedLines < maxSnippetLines; i++) {
            String lowerLine = lines[i].toLowerCase();
            for (String keyword : keywords) {
                if (lowerLine.contains(keyword)) {
                    // 添加上下文（前后各1行）
                    if (i > 0 && addedLines < maxSnippetLines) {
                        snippet.append(lines[i - 1]).append("\n");
                        addedLines++;
                    }
                    if (addedLines < maxSnippetLines) {
                        snippet.append(lines[i]).append("\n");
                        addedLines++;
                    }
                    if (i < lines.length - 1 && addedLines < maxSnippetLines) {
                        snippet.append(lines[i + 1]).append("\n");
                        addedLines++;
                    }
                    break;
                }
            }
        }
        
        if (snippet.length() == 0) {
            // 返回文档开头
            int endIndex = Math.min(content.length(), 500);
            return content.substring(0, endIndex) + "...";
        }
        
        return snippet.toString();
    }

    // Internal helper - delegates to the full method
    public String searchKnowledge(String query) {
        return searchKnowledge(query, "all");
    }

    @Tool("Search specifically for testing guidelines and conventions in Markdown documentation")
    public String searchTestingGuidelines(@P("Topic to search for (e.g., 'assertion style', 'mock setup')") String topic) {
        log.info("Tool Input - searchTestingGuidelines: topic={}", topic);
        return searchKnowledge("testing guidelines " + topic + " best practices conventions", "markdown");
    }

    @Tool("Search for existing test code examples that match a pattern")
    public String searchTestExamples(@P("What kind of test example to find (e.g., 'service layer test', 'mock repository')") String pattern) {
        log.info("Tool Input - searchTestExamples: pattern={}", pattern);
        return searchKnowledge("@Test " + pattern + " example unit test", "java");
    }

    private String fallbackSearch(String query) {
        StringBuilder result = new StringBuilder();
        // Keep simple fallback advice
        if (query.toLowerCase().contains("mockito") || query.toLowerCase().contains("mock")) {
            result.append("--- Mockito Guidelines (Fallback) ---\n");
            result.append("1. Use @ExtendWith(MockitoExtension.class) for JUnit 5.\n");
            result.append("2. Use @Mock for dependencies and @InjectMocks for the class under test.\n");
        }

        if (query.toLowerCase().contains("junit") || query.toLowerCase().contains("test")) {
            result.append("--- JUnit 5 Guidelines (Fallback) ---\n");
            result.append("1. Class should be package-private.\n");
            result.append("2. Use Assertions.assertEquals(expected, actual);\n");
        }

        if (result.length() == 0) {
            return "Knowledge base not initialized and no fallback advice for: " + query;
        }

        return result.toString();
    }
}
