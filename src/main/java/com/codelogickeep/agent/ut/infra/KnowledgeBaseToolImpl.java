package com.codelogickeep.agent.ut.infra;

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
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class KnowledgeBaseToolImpl implements KnowledgeBaseTool {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseToolImpl.class);

    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;
    private boolean isInitialized = false;

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
                // Filter for Java files only to learn coding style
                try (Stream<Path> stream = Files.find(path, 10,
                        (p, attr) -> attr.isRegularFile() && p.toString().endsWith(".java"))) {

                    List<Path> javaFiles = stream.collect(Collectors.toList());
                    if (javaFiles.isEmpty()) {
                        log.warn("No .java files found in knowledge base directory: {}", knowledgeBasePath);
                    }

                    for (Path p : javaFiles) {
                        try {
                            documents
                                    .add(FileSystemDocumentLoader.loadDocument(p.toString(), new TextDocumentParser()));
                        } catch (Exception e) {
                            log.warn("Failed to load document: {}", p, e);
                        }
                    }
                }
            } else {
                documents = Collections.singletonList(
                        FileSystemDocumentLoader.loadDocument(path.toString(), new TextDocumentParser()));
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
            log.info("Knowledge base initialized with {} documents.", documents.size());

        } catch (Exception e) {
            log.error("Failed to initialize knowledge base", e);
        }
    }

    @Override
    public String searchKnowledge(String query) {
        if (!isInitialized) {
            return fallbackSearch(query);
        }

        try {
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(embeddingModel.embed(query).content())
                    .maxResults(3)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> relevant = searchResult.matches();

            StringBuilder result = new StringBuilder();
            result.append("Knowledge Base Results (Vector Search) for: ").append(query).append("\n\n");

            boolean found = false;
            for (EmbeddingMatch<TextSegment> match : relevant) {
                if (match.score() > 0.6) {
                    found = true;
                    result.append("--- Relevance: ").append(String.format("%.2f", match.score())).append(" ---\n");
                    result.append(match.embedded().text()).append("\n\n");
                }
            }

            if (!found) {
                return "No relevant information found in knowledge base (score < 0.6).";
            }

            return result.toString();

        } catch (Exception e) {
            log.error("Error searching knowledge base", e);
            return "Error searching knowledge base: " + e.getMessage();
        }
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
