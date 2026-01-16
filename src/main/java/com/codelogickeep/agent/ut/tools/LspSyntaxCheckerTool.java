package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.framework.annotation.P;
import com.codelogickeep.agent.ut.framework.annotation.Tool;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * LSP 语法检查工具 - 使用 Eclipse JDT Language Server 进行完整语义检查
 * 
 * 功能：
 * - 编译器级别的语法和语义检查
 * - 检测 import 缺失和类型错误
 * - 检测方法签名不匹配
 * - 提供精确的错误位置和修复建议
 * 
 * 使用方式：
 * - 需要 Eclipse JDT Language Server 安装在系统中
 * - 或者指定 jdt-language-server 路径
 */
@Slf4j
public class LspSyntaxCheckerTool implements AgentTool {

    private String projectRoot;
    private Process lspProcess;
    private LanguageServer languageServer;
    private LspClient lspClient;
    private boolean initialized = false;
    
    // JDT LS 管理器 - 自动下载和管理
    private final JdtLsManager jdtLsManager = new JdtLsManager();
    
    // 诊断结果缓存
    private final Map<String, List<Diagnostic>> diagnosticsCache = new ConcurrentHashMap<>();
    private volatile CountDownLatch diagnosticsLatch = new CountDownLatch(1);
    // 诊断接收时间戳，用于判断诊断是否稳定
    private volatile long lastDiagnosticsTime = 0;

    public void setProjectRoot(String projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * Initialize LSP server for the project. Called automatically when use-lsp is enabled.
     * This is NOT a tool - it's called internally during startup.
     */
    public String initializeLsp(String projectPath) {
        
        log.info("Tool Input - initializeLsp: projectPath={}", projectPath);
        
        if (initialized) {
            return "LSP server already initialized for: " + this.projectRoot;
        }
        
        this.projectRoot = projectPath;
        
        try {
            // 1. 确保 JDT LS 可用（自动下载如果需要）
            log.info("Ensuring JDT Language Server is available...");
            if (!jdtLsManager.ensureJdtLsAvailable()) {
                return "ERROR: Failed to setup JDT Language Server. Check network connection.\n" +
                       "Manual installation: https://download.eclipse.org/jdtls/";
            }
            
            // 2. 启动 LSP 进程
            log.info("Starting JDT Language Server...");
            lspProcess = jdtLsManager.startJdtls(projectPath);
            
            // 短暂等待确保进程启动
            Thread.sleep(500);
            
            // 检查进程是否还在运行
            if (!lspProcess.isAlive()) {
                int exitCode = lspProcess.exitValue();
                return "ERROR: JDT LS process terminated unexpectedly with exit code: " + exitCode;
            }
            
            // 3. 立即初始化 LSP 连接（JDT LS 期望立即收到初始化消息）
            initializeLspConnection(projectPath);
            
            initialized = true;
            String result = "LSP server initialized successfully for: " + projectPath;
            log.info("Tool Output - initializeLsp: {}", result);
            return result;
            
        } catch (Exception e) {
            log.error("Failed to initialize LSP server", e);
            return "ERROR: Failed to initialize LSP server: " + e.getMessage();
        }
    }
    
    // getJdtLsInfo merged into getLspStatus below

    @Tool("Check Java file syntax using LSP (detects type errors, missing imports, etc.)")
    public String checkSyntaxWithLsp(
            @P("Path to the Java file to check") String filePath) {
        
        log.info("Tool Input - checkSyntaxWithLsp: path={}", filePath);
        
        if (!initialized) {
            // 尝试自动初始化
            if (projectRoot != null) {
                String initResult = initializeLsp(projectRoot);
                if (initResult.startsWith("ERROR")) {
                    return initResult;
                }
            } else {
                return "ERROR: LSP not initialized. Call initializeLsp first or provide projectRoot.";
            }
        }
        
        Path path = resolvePath(filePath);
        if (!Files.exists(path)) {
            return "ERROR: File not found: " + filePath;
        }
        
        try {
            // 1. 打开文件
            String content = Files.readString(path);
            String uri = path.toUri().toString();
            
            TextDocumentItem textDocument = new TextDocumentItem(uri, "java", 1, content);
            languageServer.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(textDocument));
            
            // 2. 等待诊断结果（LSP 会异步推送，可能分批发送）
            // 等待首次诊断
            boolean received = diagnosticsLatch.await(10, TimeUnit.SECONDS);
            
            if (!received) {
                log.warn("Timeout waiting for initial diagnostics");
            }
            
            // 3. 等待诊断稳定（确保所有诊断都已接收）
            // JDT LS 可能分批发送诊断，等待直到 500ms 内没有新诊断
            // 对于新文件或首次检查，需要更长时间让 LSP 完成项目索引
            int stableWaitMs = 500;
            int maxWaitMs = 5000; // 增加到 5 秒，给 LSP 更多时间索引项目
            int waitedMs = 0;
            while (waitedMs < maxWaitMs) {
                long timeSinceLastDiag = System.currentTimeMillis() - lastDiagnosticsTime;
                if (timeSinceLastDiag >= stableWaitMs) {
                    break; // 诊断已稳定
                }
                Thread.sleep(100);
                waitedMs += 100;
            }
            
            // 如果等待时间较长，可能 LSP 还在索引，再等待一次
            if (waitedMs >= maxWaitMs - 100) {
                log.debug("LSP may still be indexing, waiting additional 1s...");
                Thread.sleep(1000);
            }
            
            // 4. 获取诊断结果
            List<Diagnostic> diagnostics = diagnosticsCache.getOrDefault(uri, Collections.emptyList());
            
            // 5. 格式化结果
            String result = formatDiagnostics(filePath, diagnostics);
            log.info("Tool Output - checkSyntaxWithLsp: {}", 
                    result.length() > 100 ? result.substring(0, 100) + "..." : result);
            return result;
            
        } catch (Exception e) {
            log.error("LSP syntax check failed", e);
            String result = "ERROR: LSP check failed: " + e.getMessage();
            log.info("Tool Output - checkSyntaxWithLsp: {}", result);
            return result;
        }
    }

    @Tool("Check Java code content using LSP before writing to file")
    public String checkContentWithLsp(
            @P("Java source code content") String content,
            @P("Target file path (for context)") String targetPath) {
        
        log.info("Tool Input - checkContentWithLsp: targetPath={}, contentLength={}", targetPath, content.length());
        
        if (!initialized) {
            if (projectRoot != null) {
                String initResult = initializeLsp(projectRoot);
                if (initResult.startsWith("ERROR")) {
                    return initResult;
                }
            } else {
                return "ERROR: LSP not initialized. Call initializeLsp first.";
            }
        }
        
        try {
            // 创建临时文件进行检查
            Path tempFile = Files.createTempFile("lsp-check-", ".java");
            Files.writeString(tempFile, content);
            
            String uri = tempFile.toUri().toString();
            
            TextDocumentItem textDocument = new TextDocumentItem(uri, "java", 1, content);
            languageServer.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(textDocument));
            
            // 等待诊断
            Thread.sleep(2000); // 给 LSP 一点时间处理
            
            List<Diagnostic> diagnostics = diagnosticsCache.getOrDefault(uri, Collections.emptyList());
            
            // 清理临时文件
            Files.deleteIfExists(tempFile);
            
            String result = formatDiagnostics(targetPath, diagnostics);
            log.info("Tool Output - checkContentWithLsp: {}", 
                    result.length() > 100 ? result.substring(0, 100) + "..." : result);
            return result;
            
        } catch (Exception e) {
            log.error("LSP content check failed", e);
            String result = "ERROR: LSP check failed: " + e.getMessage();
            log.info("Tool Output - checkContentWithLsp: {}", result);
            return result;
        }
    }

    /**
     * Shutdown LSP server and release resources.
     * This is NOT a tool - it's called internally during application shutdown.
     */
    public String shutdownLsp() {
        log.info("Tool Input - shutdownLsp");
        
        if (!initialized) {
            return "LSP server was not running";
        }
        
        try {
            if (languageServer != null) {
                languageServer.shutdown().get(5, TimeUnit.SECONDS);
                languageServer.exit();
            }
            
            if (lspProcess != null && lspProcess.isAlive()) {
                lspProcess.destroyForcibly();
            }
            
            initialized = false;
            diagnosticsCache.clear();
            
            String result = "LSP server shutdown successfully";
            log.info("Tool Output - shutdownLsp: {}", result);
            return result;
            
        } catch (Exception e) {
            log.error("Error shutting down LSP server", e);
            return "ERROR: Failed to shutdown LSP server: " + e.getMessage();
        }
    }

    @Tool("Get LSP server status and JDT Language Server installation info")
    public String getLspStatus() {
        StringBuilder sb = new StringBuilder();
        
        // LSP 运行状态
        if (!initialized) {
            sb.append("LSP Status: NOT_INITIALIZED\n");
            sb.append("Note: LSP will be auto-initialized when use-lsp is enabled in config.\n\n");
        } else {
            sb.append("LSP Status: RUNNING\n");
            sb.append("Project Root: ").append(projectRoot).append("\n");
            sb.append("Cached Diagnostics: ").append(diagnosticsCache.size()).append(" files\n\n");
        }
        
        // JDT LS 安装信息
        sb.append("JDT Language Server Info:\n");
        sb.append(jdtLsManager.getStatus());
        
        return sb.toString();
    }

    // ==================== Private Methods ====================

    private void initializeLspConnection(String projectPath) throws Exception {
        // 创建 LSP 客户端
        lspClient = new LspClient();
        
        // 创建 Launcher
        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
            lspClient,
            lspProcess.getInputStream(),
            lspProcess.getOutputStream()
        );
        
        // 启动监听
        launcher.startListening();
        
        // 获取服务器代理
        languageServer = launcher.getRemoteProxy();
        
        // 初始化请求
        InitializeParams initParams = new InitializeParams();
        initParams.setRootUri(Paths.get(projectPath).toUri().toString());
        initParams.setCapabilities(createClientCapabilities());
        
        // 发送初始化请求（JDT LS 启动可能较慢，增加超时时间）
        log.info("Waiting for LSP server to initialize (timeout: 60s)...");
        InitializeResult initResult = languageServer.initialize(initParams).get(60, TimeUnit.SECONDS);
        log.info("LSP server initialized: {}", initResult.getCapabilities());
        
        // 发送 initialized 通知
        languageServer.initialized(new InitializedParams());
    }

    private ClientCapabilities createClientCapabilities() {
        ClientCapabilities capabilities = new ClientCapabilities();
        
        // 文本文档能力
        TextDocumentClientCapabilities textDocCaps = new TextDocumentClientCapabilities();
        
        PublishDiagnosticsCapabilities diagCaps = new PublishDiagnosticsCapabilities();
        diagCaps.setRelatedInformation(true);
        textDocCaps.setPublishDiagnostics(diagCaps);
        
        capabilities.setTextDocument(textDocCaps);
        
        // 工作区能力
        WorkspaceClientCapabilities workspaceCaps = new WorkspaceClientCapabilities();
        workspaceCaps.setWorkspaceFolders(true);
        capabilities.setWorkspace(workspaceCaps);
        
        return capabilities;
    }

    private String formatDiagnostics(String filePath, List<Diagnostic> diagnostics) {
        // 检查是否有错误级别的诊断
        boolean hasErrors = diagnostics.stream()
                .anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
        
        // 更新编译守卫状态
        if (!hasErrors) {
            CompileGuard.getInstance().markSyntaxPassed(filePath);
        } else {
            String errorSummary = diagnostics.stream()
                    .filter(d -> d.getSeverity() == DiagnosticSeverity.Error)
                    .map(Diagnostic::getMessage)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Unknown errors");
            CompileGuard.getInstance().markSyntaxFailed(filePath, errorSummary);
        }
        
        if (diagnostics.isEmpty()) {
            return "LSP_OK: No errors found in " + filePath;
        }
        
        StringBuilder sb = new StringBuilder();
        
        // 按严重程度分组
        Map<DiagnosticSeverity, List<Diagnostic>> grouped = diagnostics.stream()
                .collect(Collectors.groupingBy(Diagnostic::getSeverity));
        
        List<Diagnostic> errors = grouped.getOrDefault(DiagnosticSeverity.Error, Collections.emptyList());
        List<Diagnostic> warnings = grouped.getOrDefault(DiagnosticSeverity.Warning, Collections.emptyList());
        
        if (!errors.isEmpty()) {
            sb.append("LSP_ERRORS (").append(errors.size()).append("):\n");
            for (Diagnostic diag : errors) {
                sb.append(formatSingleDiagnostic(diag)).append("\n");
            }
        }
        
        if (!warnings.isEmpty()) {
            if (!errors.isEmpty()) sb.append("\n");
            sb.append("LSP_WARNINGS (").append(warnings.size()).append("):\n");
            for (Diagnostic diag : warnings) {
                sb.append(formatSingleDiagnostic(diag)).append("\n");
            }
        }
        
        // 添加修复建议
        sb.append("\nSUGGESTIONS:\n");
        boolean hasDependencyError = errors.stream().anyMatch(d -> 
            d.getMessage().contains("cannot be resolved") || 
            d.getMessage().contains("程序包") ||
            d.getMessage().contains("找不到符号"));
        
        if (hasDependencyError) {
            sb.append("  ⚠️ DEPENDENCY ISSUE DETECTED:\n");
            sb.append("     - Check if required dependencies are in pom.xml\n");
            sb.append("     - For JUnit: <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId></dependency>\n");
            sb.append("     - For Mockito: <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId></dependency>\n");
            sb.append("     - Run 'mvn dependency:resolve' to download dependencies\n");
        }
        if (errors.stream().anyMatch(d -> d.getMessage().contains("cannot be resolved") && !hasDependencyError)) {
            sb.append("  - Add missing import statements\n");
        }
        if (errors.stream().anyMatch(d -> d.getMessage().contains("type mismatch"))) {
            sb.append("  - Check variable types and method return types\n");
        }
        if (errors.stream().anyMatch(d -> d.getMessage().contains("is undefined"))) {
            sb.append("  - Check method names and parameters\n");
        }
        
        return sb.toString().trim();
    }

    private String formatSingleDiagnostic(Diagnostic diag) {
        Position start = diag.getRange().getStart();
        return String.format("  Line %d:%d: %s", 
                start.getLine() + 1, 
                start.getCharacter() + 1, 
                diag.getMessage());
    }

    private Path resolvePath(String filePath) {
        Path path = Paths.get(filePath);
        if (!path.isAbsolute() && projectRoot != null) {
            path = Paths.get(projectRoot, filePath);
        }
        return path;
    }

    // ==================== LSP Client Implementation ====================

    private class LspClient implements LanguageClient {
        
        @Override
        public void telemetryEvent(Object object) {
            log.debug("LSP telemetry: {}", object);
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            String uri = diagnostics.getUri();
            List<Diagnostic> diags = diagnostics.getDiagnostics();
            
            log.debug("Received {} diagnostics for {}", diags.size(), uri);
            diagnosticsCache.put(uri, diags);
            lastDiagnosticsTime = System.currentTimeMillis(); // 记录接收时间
            diagnosticsLatch.countDown();
        }

        @Override
        public void showMessage(MessageParams messageParams) {
            log.info("LSP message [{}]: {}", messageParams.getType(), messageParams.getMessage());
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            log.info("LSP message request: {}", requestParams.getMessage());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
            log.debug("LSP log: {}", message.getMessage());
        }
        
        @Override
        public CompletableFuture<Void> registerCapability(RegistrationParams params) {
            log.debug("LSP registerCapability: {}", params.getRegistrations());
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
            log.debug("LSP unregisterCapability: {}", params.getUnregisterations());
            return CompletableFuture.completedFuture(null);
        }
    }
}
