package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.config.AppConfig;
import com.codelogickeep.agent.ut.framework.annotation.P;
import com.codelogickeep.agent.ut.framework.annotation.Tool;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 知识库工具（支持索引持久化）
 * 
 * 特性：
 * - 基于关键词的简单搜索
 * - 支持索引持久化，避免每次重建
 * - 自动检测文件变更，增量更新
 */
public class KnowledgeBaseTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTool.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // Supported file extensions for knowledge base
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".java", ".md", ".txt", ".xml", ".yml", ".yaml", ".json"
    );
    
    // 缓存目录名
    private static final String CACHE_DIR_NAME = ".utagent";
    private static final String CACHE_FILE_NAME = "kb-cache.json";

    private boolean isInitialized = false;
    private int documentCount = 0;
    private String indexedPath = null;
    private List<DocumentInfo> documents = new ArrayList<>();
    private boolean cacheEnabled = true;

    /**
     * 文档信息（简化版）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DocumentInfo {
        public String path;
        public String content;
        public String type;
        public String fileName;
        public long lastModified;
        public String contentHash;
        
        public DocumentInfo() {} // for Jackson
        
        DocumentInfo(String path, String content, String type, String fileName, long lastModified) {
            this.path = path;
            this.content = content;
            this.type = type;
            this.fileName = fileName;
            this.lastModified = lastModified;
            this.contentHash = computeHash(content);
        }
        
        private static String computeHash(String content) {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(content.getBytes());
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (Exception e) {
                return String.valueOf(content.hashCode());
            }
        }
    }
    
    /**
     * 缓存元数据
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CacheMetadata {
        public String indexedPath;
        public long createdAt;
        public int documentCount;
        public Map<String, Long> fileModTimes = new HashMap<>();
        public List<DocumentInfo> documents = new ArrayList<>();
        
        public CacheMetadata() {} // for Jackson
    }

    /**
     * 初始化知识库（带缓存支持）
     */
    public void init(AppConfig config, String knowledgeBasePath) {
        init(config, knowledgeBasePath, true);
    }
    
    /**
     * 初始化知识库
     * 
     * @param config 应用配置
     * @param knowledgeBasePath 知识库路径
     * @param useCache 是否使用缓存
     */
    public void init(AppConfig config, String knowledgeBasePath, boolean useCache) {
        if (knowledgeBasePath == null || knowledgeBasePath.isEmpty()) {
            log.warn("No knowledge base path provided. Knowledge base tool will use fallback logic.");
            return;
        }

        try {
            Path path = Paths.get(knowledgeBasePath).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                log.error("Knowledge base path does not exist: {}", knowledgeBasePath);
                return;
            }

            this.cacheEnabled = useCache;
            String normalizedPath = path.toString();
            
            // 尝试从缓存加载
            if (useCache && tryLoadFromCache(normalizedPath)) {
                log.info("Knowledge base loaded from cache ({} documents)", documentCount);
                return;
            }

            log.info("Initializing knowledge base from: {}", normalizedPath);
            long startTime = System.currentTimeMillis();

            documents = new ArrayList<>();
            Map<String, Long> fileModTimes = new HashMap<>();

            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.find(path, 10,
                        (p, attr) -> attr.isRegularFile() && isSupportedFile(p.toString()))) {

                    List<Path> files = stream.collect(Collectors.toList());
                    if (files.isEmpty()) {
                        log.warn("No supported files found in knowledge base directory: {}", normalizedPath);
                    }

                    for (Path p : files) {
                        try {
                            String content = Files.readString(p);
                            String type = getFileType(p.toString());
                            long lastMod = Files.getLastModifiedTime(p).toMillis();
                            documents.add(new DocumentInfo(p.toString(), content, type, p.getFileName().toString(), lastMod));
                            fileModTimes.put(p.toString(), lastMod);
                            log.debug("Loaded document: {} ({})", p.getFileName(), type);
                        } catch (Exception e) {
                            log.warn("Failed to load document: {}", p, e);
                        }
                    }
                }
            } else {
                String content = Files.readString(path);
                String type = getFileType(path.toString());
                long lastMod = Files.getLastModifiedTime(path).toMillis();
                documents.add(new DocumentInfo(path.toString(), content, type, path.getFileName().toString(), lastMod));
                fileModTimes.put(path.toString(), lastMod);
            }

            if (documents.isEmpty()) {
                log.warn("No valid documents found in {}", normalizedPath);
                return;
            }

            this.isInitialized = true;
            this.documentCount = documents.size();
            this.indexedPath = normalizedPath;
            
            long elapsedMs = System.currentTimeMillis() - startTime;
            long javaCount = documents.stream().filter(d -> "java".equals(d.type)).count();
            long mdCount = documents.stream().filter(d -> "markdown".equals(d.type)).count();
            long otherCount = documentCount - javaCount - mdCount;
            
            log.info("Knowledge base initialized with {} documents (Java: {}, Markdown: {}, Other: {}) in {}ms",
                    documentCount, javaCount, mdCount, otherCount, elapsedMs);

            // 保存到缓存
            if (useCache) {
                saveToCache(normalizedPath, fileModTimes);
            }

        } catch (Exception e) {
            log.error("Failed to initialize knowledge base", e);
        }
    }
    
    /**
     * 尝试从缓存加载
     */
    private boolean tryLoadFromCache(String knowledgeBasePath) {
        Path cachePath = getCachePath(knowledgeBasePath);
        if (!Files.exists(cachePath)) {
            log.debug("No cache file found at: {}", cachePath);
            return false;
        }
        
        try {
            CacheMetadata cache = mapper.readValue(cachePath.toFile(), CacheMetadata.class);
            
            // 验证缓存路径匹配
            if (!knowledgeBasePath.equals(cache.indexedPath)) {
                log.debug("Cache path mismatch: {} vs {}", knowledgeBasePath, cache.indexedPath);
                return false;
            }
            
            // 验证文件未变更
            if (!isCacheValid(cache, knowledgeBasePath)) {
                log.info("Cache is stale, will rebuild index");
                return false;
            }
            
            // 加载缓存数据
            this.documents = cache.documents;
            this.documentCount = cache.documentCount;
            this.indexedPath = cache.indexedPath;
            this.isInitialized = true;
            
            log.info("Loaded knowledge base from cache: {} documents (created: {})",
                    documentCount, new java.util.Date(cache.createdAt));
            return true;
            
        } catch (Exception e) {
            log.warn("Failed to load cache, will rebuild: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 检查缓存是否有效
     */
    private boolean isCacheValid(CacheMetadata cache, String knowledgeBasePath) {
        try {
            Path basePath = Paths.get(knowledgeBasePath);
            
            // 获取当前所有文件
            List<Path> currentFiles;
            if (Files.isDirectory(basePath)) {
                try (Stream<Path> stream = Files.find(basePath, 10,
                        (p, attr) -> attr.isRegularFile() && isSupportedFile(p.toString()))) {
                    currentFiles = stream.collect(Collectors.toList());
                }
            } else {
                currentFiles = List.of(basePath);
            }
            
            // 检查文件数量
            if (currentFiles.size() != cache.fileModTimes.size()) {
                log.debug("File count changed: {} -> {}", cache.fileModTimes.size(), currentFiles.size());
                return false;
            }
            
            // 检查每个文件的修改时间
            for (Path file : currentFiles) {
                String filePath = file.toString();
                Long cachedModTime = cache.fileModTimes.get(filePath);
                if (cachedModTime == null) {
                    log.debug("New file detected: {}", filePath);
                    return false;
                }
                
                long currentModTime = Files.getLastModifiedTime(file).toMillis();
                if (currentModTime != cachedModTime) {
                    log.debug("File modified: {} (cached: {}, current: {})", 
                            file.getFileName(), cachedModTime, currentModTime);
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            log.warn("Error validating cache: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 保存索引到缓存
     */
    private void saveToCache(String knowledgeBasePath, Map<String, Long> fileModTimes) {
        try {
            Path cachePath = getCachePath(knowledgeBasePath);
            Files.createDirectories(cachePath.getParent());
            
            CacheMetadata cache = new CacheMetadata();
            cache.indexedPath = knowledgeBasePath;
            cache.createdAt = System.currentTimeMillis();
            cache.documentCount = documents.size();
            cache.fileModTimes = fileModTimes;
            cache.documents = documents;
            
            mapper.writeValue(cachePath.toFile(), cache);
            log.info("Saved knowledge base cache to: {}", cachePath);
            
        } catch (Exception e) {
            log.warn("Failed to save cache: {}", e.getMessage());
        }
    }
    
    /**
     * 获取缓存文件路径
     */
    private Path getCachePath(String knowledgeBasePath) {
        // 在知识库目录的父目录下创建 .utagent 缓存目录
        Path kbPath = Paths.get(knowledgeBasePath);
        Path parent = kbPath.getParent();
        if (parent == null) {
            parent = Paths.get(System.getProperty("user.home"));
        }
        return parent.resolve(CACHE_DIR_NAME).resolve(CACHE_FILE_NAME);
    }
    
    /**
     * 清除缓存
     */
    public void clearCache() {
        if (indexedPath != null) {
            Path cachePath = getCachePath(indexedPath);
            try {
                Files.deleteIfExists(cachePath);
                log.info("Cache cleared: {}", cachePath);
            } catch (Exception e) {
                log.warn("Failed to clear cache: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 强制重建索引（忽略缓存）
     */
    public void rebuild(AppConfig config, String knowledgeBasePath) {
        clearCache();
        init(config, knowledgeBasePath, true);
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
            sb.append("Cache Enabled: ").append(cacheEnabled).append("\n");
            if (cacheEnabled && indexedPath != null) {
                Path cachePath = getCachePath(indexedPath);
                sb.append("Cache Location: ").append(cachePath).append("\n");
                sb.append("Cache Exists: ").append(Files.exists(cachePath)).append("\n");
            }
            sb.append("Note: Using keyword-based search (embedding disabled)\n");
        } else {
            sb.append("Status: Not initialized. Use -kb <path> to specify a knowledge base.\n");
        }
        sb.append("=============================\n");
        String result = sb.toString();
        log.info("Tool Output - getKnowledgeBaseStatus: initialized={}", isInitialized);
        return result;
    }
    
    @Tool("Rebuild knowledge base index, clearing any cached data")
    public String rebuildKnowledgeBase() {
        log.info("Tool Input - rebuildKnowledgeBase");
        if (indexedPath == null) {
            return "ERROR: Knowledge base not initialized. Cannot rebuild.";
        }
        
        String path = indexedPath;
        clearCache();
        isInitialized = false;
        documents.clear();
        documentCount = 0;
        
        init(null, path, true);
        
        String result = String.format("Knowledge base rebuilt: %d documents indexed from %s", documentCount, path);
        log.info("Tool Output - rebuildKnowledgeBase: {}", result);
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
