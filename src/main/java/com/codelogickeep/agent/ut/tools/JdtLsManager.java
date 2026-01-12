package com.codelogickeep.agent.ut.tools;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * JDT Language Server 管理器 - 自动下载和管理 Eclipse JDT LS
 * 
 * 功能：
 * - 自动检测本地 JDT LS 安装
 * - 自动下载 JDT LS（如果未安装）到 Agent 工具所在目录
 * - 管理 JDT LS 版本
 * - 启动 JDT LS 进程
 */
@Slf4j
public class JdtLsManager {

    // JDT LS 下载配置
    // 版本 1.44.0 支持 JDK 21+
    private static final String JDTLS_VERSION = "1.50.0";
    private static final String JDTLS_DOWNLOAD_URL = 
            "https://download.eclipse.org/jdtls/milestones/1.50.0/jdt-language-server-1.50.0-202509041425.tar.gz";
    private static final String JDTLS_FILENAME = "jdt-language-server-1.50.0.tar.gz";

    // 本地目录名
    private static final String JDTLS_DIR_NAME = "jdtls";

    private final Path agentDir;  // Agent JAR 所在目录
    private final Path jdtlsDir;
    private Path jdtlsLauncher;

    public JdtLsManager() {
        // 使用 Agent JAR 所在目录作为根目录
        this.agentDir = getAgentDirectory();
        this.jdtlsDir = agentDir.resolve(JDTLS_DIR_NAME);
        log.info("JDT LS Manager initialized. Agent dir: {}, JDTLS dir: {}", agentDir, jdtlsDir);
    }
    
    /**
     * 获取 Agent JAR 所在目录
     */
    private static Path getAgentDirectory() {
        try {
            // 获取当前类所在的 JAR 文件路径
            Path jarPath = Paths.get(JdtLsManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            
            // 如果是 JAR 文件，返回其父目录
            if (Files.isRegularFile(jarPath)) {
                return jarPath.getParent();
            }
            // 如果是目录（开发环境），返回当前工作目录
            return Paths.get(System.getProperty("user.dir"));
        } catch (Exception e) {
            log.warn("Failed to determine agent directory, using current directory", e);
            return Paths.get(System.getProperty("user.dir"));
        }
    }

    /**
     * 确保 JDT LS 可用（如果需要则自动下载）
     * 
     * @return JDT LS 是否可用
     */
    public boolean ensureJdtLsAvailable() {
        log.info("Checking JDT Language Server availability...");
        
        // 1. 检查系统已安装的 JDTLS
        String systemJdtls = findSystemJdtls();
        if (systemJdtls != null) {
            log.info("Found system-installed JDT LS: {}", systemJdtls);
            this.jdtlsLauncher = Paths.get(systemJdtls);
            return true;
        }
        
        // 2. 检查缓存的 JDTLS
        if (isCachedJdtlsValid()) {
            log.info("Found cached JDT LS: {}", jdtlsLauncher);
            return true;
        }
        
        // 3. 下载 JDTLS
        log.info("JDT LS not found. Downloading...");
        return downloadAndExtractJdtls();
    }

    /**
     * 获取 JDT LS 启动命令
     */
    public List<String> getJdtlsCommand(String projectPath, String workspaceDir) {
        if (jdtlsLauncher == null) {
            throw new IllegalStateException("JDT LS not initialized. Call ensureJdtLsAvailable() first.");
        }
        
        List<String> command = new ArrayList<>();
        
        String os = System.getProperty("os.name").toLowerCase();
        
        if (os.contains("win")) {
            // Windows
            if (jdtlsLauncher.toString().endsWith(".bat") || jdtlsLauncher.toString().endsWith(".cmd")) {
                command.add(jdtlsLauncher.toString());
            } else {
                command.add("java");
                command.addAll(getJavaArgs(workspaceDir));
            }
        } else {
            // Linux/macOS
            if (Files.isExecutable(jdtlsLauncher)) {
                command.add(jdtlsLauncher.toString());
            } else {
                command.add("java");
                command.addAll(getJavaArgs(workspaceDir));
            }
        }
        
        // 添加工作空间参数
        command.add("-data");
        command.add(workspaceDir);
        
        return command;
    }

    /**
     * 启动 JDT LS 进程
     */
    public Process startJdtls(String projectPath) throws IOException {
        // 创建工作空间目录
        String workspaceDir = createWorkspaceDir(projectPath);
        
        List<String> command = getJdtlsCommand(projectPath, workspaceDir);
        log.info("Starting JDT LS with command: {}", String.join(" ", command));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(projectPath));
        pb.redirectErrorStream(false);
        
        // 设置环境变量
        pb.environment().put("CLIENT_HOST", "127.0.0.1");
        pb.environment().put("CLIENT_PORT", "0");
        
        return pb.start();
    }

    /**
     * 获取 JDT LS 状态信息
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("JDT Language Server Status:\n");
        sb.append("  Preferred Version: ").append(JDTLS_VERSION).append(" (JDK 21+ compatible)\n");
        sb.append("  Install Dir: ").append(agentDir).append("\n");
        
        if (jdtlsLauncher != null && Files.exists(jdtlsLauncher)) {
            sb.append("  Status: AVAILABLE\n");
            sb.append("  Location: ").append(jdtlsLauncher).append("\n");
        } else {
            String systemJdtls = findSystemJdtls();
            if (systemJdtls != null) {
                sb.append("  Status: SYSTEM_INSTALLED\n");
                sb.append("  Location: ").append(systemJdtls).append("\n");
            } else if (isCachedJdtlsValid()) {
                sb.append("  Status: CACHED\n");
                sb.append("  Location: ").append(jdtlsLauncher).append("\n");
            } else {
                sb.append("  Status: NOT_INSTALLED\n");
                sb.append("  Action: Call ensureJdtLsAvailable() to download\n");
                sb.append("  Supported Versions: 1.38.0, 1.37.0, 1.36.0, 1.31.0\n");
            }
        }
        
        return sb.toString();
    }

    // ==================== Private Methods ====================

    private String findSystemJdtls() {
        // 检查环境变量
        String jdtlsPath = System.getenv("JDTLS_PATH");
        if (jdtlsPath != null && Files.exists(Paths.get(jdtlsPath))) {
            return jdtlsPath;
        }
        
        // 检查 PATH 中的命令
        String[] commands = {"jdtls", "jdt-language-server"};
        for (String cmd : commands) {
            if (isCommandAvailable(cmd)) {
                return cmd;
            }
        }
        
        // 检查常见安装位置
        String[] commonPaths = {
            "/usr/local/bin/jdtls",
            "/opt/homebrew/bin/jdtls",
            System.getProperty("user.home") + "/.local/share/nvim/mason/bin/jdtls",
            System.getProperty("user.home") + "/eclipse.jdt.ls/bin/jdtls",
            "C:\\Program Files\\jdtls\\bin\\jdtls.bat",
            "C:\\jdtls\\bin\\jdtls.bat"
        };
        
        for (String path : commonPaths) {
            if (Files.exists(Paths.get(path))) {
                return path;
            }
        }
        
        return null;
    }

    private boolean isCommandAvailable(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb.command("where", command);
            } else {
                pb.command("which", command);
            }
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isCachedJdtlsValid() {
        Path launcher = findCachedLauncher();
        if (launcher != null && Files.exists(launcher)) {
            this.jdtlsLauncher = launcher;
            return true;
        }
        return false;
    }

    private Path findCachedLauncher() {
        if (!Files.exists(jdtlsDir)) {
            return null;
        }
        
        // 查找 bin/jdtls 或 jdt-language-server-*.jar
        String os = System.getProperty("os.name").toLowerCase();
        
        // 查找启动脚本
        Path binDir = jdtlsDir.resolve("bin");
        if (Files.exists(binDir)) {
            if (os.contains("win")) {
                Path batFile = binDir.resolve("jdtls.bat");
                if (Files.exists(batFile)) return batFile;
            } else {
                Path shFile = binDir.resolve("jdtls");
                if (Files.exists(shFile)) return shFile;
            }
        }
        
        // 查找 launcher jar
        try {
            Path pluginsDir = jdtlsDir.resolve("plugins");
            if (Files.exists(pluginsDir)) {
                return Files.list(pluginsDir)
                        .filter(p -> p.getFileName().toString().startsWith("org.eclipse.equinox.launcher_"))
                        .filter(p -> p.toString().endsWith(".jar"))
                        .findFirst()
                        .orElse(null);
            }
        } catch (IOException e) {
            log.warn("Error finding launcher jar", e);
        }
        
        return null;
    }

    private boolean downloadAndExtractJdtls() {
        try {
            // 创建安装目录
            Files.createDirectories(jdtlsDir);
            
            Path downloadFile = agentDir.resolve(JDTLS_FILENAME);
            
            log.info("Downloading JDT LS {} (supports JDK 21+)...", JDTLS_VERSION);
            log.info("URL: {}", JDTLS_DOWNLOAD_URL);
            
            if (downloadFile(JDTLS_DOWNLOAD_URL, downloadFile)) {
                // 等待文件句柄完全释放（Windows 需要）
                Thread.sleep(500);
                
                // 解压
                log.info("Extracting JDT LS to: {}", jdtlsDir);
                extractTarGz(downloadFile, jdtlsDir);
                
                // 清理下载文件
                Files.deleteIfExists(downloadFile);
                
                // 验证
                if (isCachedJdtlsValid()) {
                    log.info("JDT LS {} installed successfully: {}", JDTLS_VERSION, jdtlsLauncher);
                    return true;
                } else {
                    log.error("JDT LS extraction failed - launcher not found");
                    return false;
                }
            }
            
            log.error("Failed to download JDT LS");
            return false;
            
        } catch (Exception e) {
            log.error("Failed to download/extract JDT LS", e);
            return false;
        }
    }

    private boolean downloadFile(String urlStr, Path destination) {
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(urlStr).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);  // 增加读取超时
            conn.setRequestProperty("User-Agent", "unit-test-agent-4j");
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.warn("Download failed with status: {}", responseCode);
                return false;
            }
            
            long totalSize = conn.getContentLengthLong();
            log.info("Downloading {} ({} MB)...", JDTLS_FILENAME, totalSize / 1024 / 1024);
            
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(destination)) {
                
                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int bytesRead;
                int lastProgress = 0;
                
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;
                    
                    if (totalSize > 0) {
                        int progress = (int) (downloaded * 100 / totalSize);
                        if (progress >= lastProgress + 10) {
                            log.info("Download progress: {}%", progress);
                            lastProgress = progress;
                        }
                    }
                }
                out.flush();
            }
            
            log.info("Download completed: {}", destination);
            return true;
            
        } catch (Exception e) {
            log.warn("Download failed: {}", e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void extractTarGz(Path tarGzFile, Path destDir) throws IOException {
        // 使用系统命令解压（更可靠）
        String os = System.getProperty("os.name").toLowerCase();
        
        ProcessBuilder pb;
        if (os.contains("win")) {
            // Windows - 使用 tar（Windows 10+ 内置）
            pb = new ProcessBuilder("tar", "-xzf", tarGzFile.toString(), "-C", destDir.toString());
        } else {
            // Linux/macOS
            pb = new ProcessBuilder("tar", "-xzf", tarGzFile.toString(), "-C", destDir.toString());
        }
        
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        // 读取输出
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("tar: {}", line);
            }
        }
        
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("tar extraction failed with exit code: " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Extraction interrupted", e);
        }
    }

    private List<String> getJavaArgs(String workspaceDir) {
        List<String> args = new ArrayList<>();
        
        // 查找 launcher jar
        Path launcherJar = jdtlsLauncher;
        if (launcherJar != null && launcherJar.toString().endsWith(".jar")) {
            args.add("-jar");
            args.add(launcherJar.toString());
        }
        
        // 配置目录
        Path configDir = jdtlsDir.resolve("config_" + getConfigSuffix());
        if (Files.exists(configDir)) {
            args.add("-configuration");
            args.add(configDir.toString());
        }
        
        return args;
    }

    private String getConfigSuffix() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "win";
        } else if (os.contains("mac")) {
            return "mac";
        } else {
            return "linux";
        }
    }

    private String createWorkspaceDir(String projectPath) throws IOException {
        Path workspaceDir = Paths.get(System.getProperty("java.io.tmpdir"),
                "jdtls-workspace",
                String.valueOf(projectPath.hashCode()));
        Files.createDirectories(workspaceDir);
        return workspaceDir.toString();
    }

    /**
     * 清理缓存的 JDT LS
     */
    public void cleanCache() throws IOException {
        if (Files.exists(jdtlsDir)) {
            log.info("Cleaning JDT LS cache: {}", jdtlsDir);
            Files.walk(jdtlsDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path);
                        }
                    });
        }
    }
}
