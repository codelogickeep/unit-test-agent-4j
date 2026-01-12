package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.config.AppConfig;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class KnowledgeBaseTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTool.class);

    // Supported file extensions for knowledge base
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".java", ".md", ".txt", ".xml", ".yml", ".yaml", ".json"
    );

    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;
    private boolean isInitialized = false;
    private int documentCount = 0;
    private String indexedPath = null;

    // Use AppConfig for consistency, though MiniLm doesn't strictly need it unless
    // configured otherwise
    public void init(AppConfig config, String knowledgeBasePath) {
        if (knowledgeBasePath == null || knowledgeBasePath.isEmpty()) {
            log.warn("No knowledge base path provided. Knowledge base tool will use fallback/empty logic.");
            return;
        }

        try {
            Path path = Paths.get(knowledgeBasePath);
            if (!Files.exists(path)) {
                log.error("Knowledge base path does not exist: {}", knowledgeBasePath);
                return;
            }

            log.info("Initializing knowledge base from: {}", knowledgeBasePath);

            // Use local embedding model
            this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
            this.embeddingStore = new InMemoryEmbeddingStore<>();

            List<Document> documents = new ArrayList<>();
            if (Files.isDirectory(path)) {
                // Load all supported file types
                try (Stream<Path> stream = Files.find(path, 10,
                        (p, attr) -> attr.isRegularFile() && isSupportedFile(p.toString()))) {

                    List<Path> files = stream.collect(Collectors.toList());
                    if (files.isEmpty()) {
                        log.warn("No supported files found in knowledge base directory: {}", knowledgeBasePath);
                    }

                    for (Path p : files) {
                        try {
                            Document doc = FileSystemDocumentLoader.loadDocument(p.toString(), new TextDocumentParser());
                            // Add metadata for source tracking
                            doc.metadata().put("source", p.getFileName().toString());
                            doc.metadata().put("type", getFileType(p.toString()));
                            documents.add(doc);
                            log.debug("Loaded document: {} ({})", p.getFileName(), getFileType(p.toString()));
                        } catch (Exception e) {
                            log.warn("Failed to load document: {}", p, e);
                        }
                    }
                }
            } else {
                Document doc = FileSystemDocumentLoader.loadDocument(path.toString(), new TextDocumentParser());
                doc.metadata().put("source", path.getFileName().toString());
                doc.metadata().put("type", getFileType(path.toString()));
                documents.add(doc);
            }

            if (documents.isEmpty()) {
                log.warn("No valid documents found in {}", knowledgeBasePath);
                return;
            }

            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .embeddingModel(embeddingModel)
                    .embeddingStore(embeddingStore)
                    .documentSplitter(DocumentSplitters.recursive(500, 50))
                    .build();

            ingestor.ingest(documents);

            this.isInitialized = true;
            this.documentCount = documents.size();
            this.indexedPath = knowledgeBasePath;
            log.info("Knowledge base initialized with {} documents (Java: {}, Markdown: {}, Other: {})",
                    documents.size(),
                    documents.stream().filter(d -> "java".equals(d.metadata().getString("type"))).count(),
                    documents.stream().filter(d -> "markdown".equals(d.metadata().getString("type"))).count(),
                    documents.stream().filter(d -> "other".equals(d.metadata().getString("type"))).count()
            );

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
        } else {
            sb.append("Status: Not initialized. Use -kb <path> to specify a knowledge base.\n");
        }
        sb.append("=============================\n");
        String result = sb.toString();
        log.info("Tool Output - getKnowledgeBaseStatus: initialized={}", isInitialized);
        return result;
    }

    @Tool("Search for information in the knowledge base. Use this to find existing unit test examples, coding guidelines, or project-specific patterns to ensure generated tests match the project style.")
    public String searchKnowledge(
            @P("Search query or keywords") String query,
            @P("Optional: filter by document type (java/markdown/xml/yaml/all). Default: all") String filterType
    ) {
        log.info("Tool Input - searchKnowledge: query={}, filterType={}", query, filterType);
        if (!isInitialized) {
            return fallbackSearch(query);
        }

        try {
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(embeddingModel.embed(query).content())
                    .maxResults(5) // Increased from 3 to 5
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> relevant = searchResult.matches();

            StringBuilder result = new StringBuilder();
            result.append("Knowledge Base Results for: ").append(query).append("\n");
            if (filterType != null && !"all".equalsIgnoreCase(filterType)) {
                result.append("(Filtered by type: ").append(filterType).append(")\n");
            }
            result.append("\n");

            boolean found = false;
            for (EmbeddingMatch<TextSegment> match : relevant) {
                if (match.score() > 0.5) { // Lowered threshold from 0.6 to 0.5
                    // Apply type filter if specified
                    String docType = match.embedded().metadata().getString("type");
                    if (filterType != null && !"all".equalsIgnoreCase(filterType) && !filterType.equalsIgnoreCase(docType)) {
                        continue;
                    }
                    
                    found = true;
                    String source = match.embedded().metadata().getString("source");
                    result.append("--- Source: ").append(source != null ? source : "unknown");
                    result.append(" | Type: ").append(docType != null ? docType : "unknown");
                    result.append(" | Relevance: ").append(String.format("%.2f", match.score())).append(" ---\n");
                    result.append(match.embedded().text()).append("\n\n");
                }
            }

            if (!found) {
                String noResultsMsg = "No relevant information found in knowledge base (score < 0.5).";
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
