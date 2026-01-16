# Unit Test Agent 4J - 自动构建脚本 (Windows)
#
# 使用方法:
#   irm https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/build.ps1 | iex
#   或者
#   powershell -c "irm https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/build.ps1 | iex"
#

$ErrorActionPreference = "Stop"

# 配置
$REPO = "codelogickeep/unit-test-agent-4j"
# 如果未指定版本，自动获取最新 release 版本
# 用户可通过 $env:VERSION = "v2.1.0" 指定特定版本
$VERSION = if ($env:VERSION) { $env:VERSION } else { $null }
$INSTALL_DIR = "$env:USERPROFILE\.utagent"
$BUILD_DIR = "$env:USERPROFILE\.utagent-build"

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Write-Error {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

# 检查环境
function Test-Environment {
    Write-Info "检查构建环境..."

    # 检查 Java
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCmd) {
        Write-Error "未找到 Java。请安装 JDK 21 或更高版本。"
        Write-Host "  下载地址: https://adoptium.net/"
        exit 1
    }

    $javaVersion = & java -version 2>&1 | Select-Object -First 1
    if ($javaVersion -match 'version "?(\d+)') {
        $majorVersion = [int]$matches[1]
        if ($majorVersion -lt 21) {
            Write-Error "Java 版本过低。当前版本: $majorVersion，需要: 21+"
            exit 1
        }
    }
    Write-Info "✓ $javaVersion"

    # 检查 Maven
    $mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvnCmd) {
        Write-Error "未找到 Maven。请安装 Maven 3.8 或更高版本。"
        Write-Host "  下载地址: https://maven.apache.org/download.cgi"
        Write-Host "  或使用: winget install Apache.Maven"
        exit 1
    }

    $mvnVersion = & mvn -version | Select-Object -First 1
    Write-Info "✓ $mvnVersion"

    # 检查 Git
    $gitCmd = Get-Command git -ErrorAction SilentlyContinue
    if (-not $gitCmd) {
        Write-Error "未找到 Git。请安装 Git。"
        Write-Host "  下载地址: https://git-scm.com/download/win"
        Write-Host "  或使用: winget install Git.Git"
        exit 1
    }

    $gitVersion = & git --version
    Write-Info "✓ $gitVersion"
}

# 获取最新版本（静默获取，仅返回版本号）
function Get-LatestVersion {
    try {
        # 尝试从 GitHub API 获取最新 release
        $response = Invoke-RestMethod -Uri "https://api.github.com/repos/$REPO/releases/latest" -ErrorAction SilentlyContinue
        if ($response.tag_name) {
            return $response.tag_name
        }
    } catch { }
    
    try {
        # 尝试从 git ls-remote 获取最新 tag
        $tags = & git ls-remote --tags --sort=-version:refname "https://github.com/$REPO.git" 2>$null
        if ($tags) {
            $latestTag = ($tags | Select-Object -First 1) -replace '.*refs/tags/', '' -replace '\^{}', ''
            if ($latestTag) {
                return $latestTag
            }
        }
    } catch { }
    
    # 如果都失败了，使用 main 分支
    return "main"
}

# 克隆源码
function Invoke-CloneSource {
    Write-Info "克隆源码..."

    if (Test-Path $BUILD_DIR) {
        Write-Warn "构建目录已存在，删除旧版本..."
        Remove-Item -Recurse -Force $BUILD_DIR
    }

    # 如果未指定版本，自动获取最新版本
    if (-not $VERSION) {
        Write-Info "获取最新版本..."
        $script:VERSION = Get-LatestVersion
        if ($VERSION -eq "main") {
            Write-Warn "无法获取最新 release，使用 main 分支"
        } else {
            Write-Info "✓ 最新版本: $VERSION"
        }
    }
    
    Write-Info "使用版本: $VERSION"
    & git clone --depth 1 --branch $VERSION "https://github.com/$REPO.git" $BUILD_DIR
    if ($LASTEXITCODE -ne 0) {
        throw "Git clone 失败"
    }

    Set-Location $BUILD_DIR
    Write-Info "✓ 源码已克隆到: $BUILD_DIR"
}

# 构建项目
function Invoke-BuildProject {
    Write-Info "开始构建项目..."
    Write-Info "这可能需要 2-5 分钟，请耐心等待..."

    & mvn clean package -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 构建失败"
    }

    if (-not (Test-Path "target\utagent.jar")) {
        throw "构建失败：未找到 utagent.jar"
    }

    $jarSize = (Get-Item "target\utagent.jar").Length / 1MB
    Write-Info "✓ 构建成功！JAR 大小: $([math]::Round($jarSize, 2)) MB"
}

# 安装
function Install-UTAgent {
    Write-Info "安装到: $INSTALL_DIR"

    # 创建安装目录
    if (-not (Test-Path $INSTALL_DIR)) {
        New-Item -ItemType Directory -Path $INSTALL_DIR -Force | Out-Null
    }

    # 复制 JAR
    Copy-Item "target\utagent.jar" "$INSTALL_DIR\"

    # 创建批处理文件
    $batchContent = '@echo off
java -jar "%~dp0utagent.jar" %*'
    $batchContent | Out-File "$INSTALL_DIR\utagent.bat" -Encoding ASCII

    # 创建 PowerShell 脚本
    $psContent = '$jarPath = Join-Path $PSScriptRoot "utagent.jar"
java -jar $jarPath $args'
    $psContent | Out-File "$INSTALL_DIR\utagent.ps1" -Encoding UTF8

    # 创建配置文件
    if (-not (Test-Path "$INSTALL_DIR\agent.yml")) {
        $configContent = @"
# Unit Test Agent 4J 配置文件
llm:
  protocol: openai
  apiKey: ${env:OPENAI_API_KEY}
  model: gpt-4

workflow:
  interactive: false
  use-lsp: false
  enableMutationTesting: false
  maxFeedbackLoopIterations: 3
"@
        $configContent | Out-File "$INSTALL_DIR\agent.yml" -Encoding UTF8
    }

    Write-Info "✓ 安装完成"
}

# 清理构建文件
function Invoke-Cleanup {
    Write-Info "清理构建文件..."
    Remove-Item -Recurse -Force $BUILD_DIR -ErrorAction SilentlyContinue
    Write-Info "✓ 清理完成"
}

# 显示完成信息
function Show-Completion {
    Write-Host ""
    Write-Info "════════════════════════════════════════════════════════════"
    Write-Info "  Unit Test Agent 4J 安装成功！"
    Write-Info "════════════════════════════════════════════════════════════"
    Write-Host ""
    Write-Host "  安装位置: $INSTALL_DIR"
    Write-Host "  可执行文件: $INSTALL_DIR\utagent.bat"
    Write-Host "  配置文件: $INSTALL_DIR\agent.yml"
    Write-Host ""
    Write-Host "  下一步操作:"
    Write-Host ""
    Write-Host "  1. 添加到 PATH:"
    Write-Host "     - 右键「此电脑」-> 属性 -> 高级系统设置 -> 环境变量"
    Write-Host "     - 在用户变量的 Path 中添加: $INSTALL_DIR"
    Write-Host ""
    Write-Host "  2. 配置 API Key:"
    Write-Host "     `$env:OPENAI_API_KEY = 'your-api-key'"
    Write-Host ""
    Write-Host "  3. 运行测试生成:"
    Write-Host "     utagent --target path\to\Class.java"
    Write-Host ""
    Write-Host "  或者使用完整路径:"
    Write-Host "     $INSTALL_DIR\utagent.bat --target path\to\Class.java"
    Write-Host ""
    Write-Info "════════════════════════════════════════════════════════════"
    Write-Host ""
}

# 主函数
function Main {
    Write-Info "开始安装 Unit Test Agent 4J (从源码构建)..."
    Write-Host ""

    Test-Environment
    Invoke-CloneSource
    Invoke-BuildProject
    Install-UTAgent
    Invoke-Cleanup
    Show-Completion
}

try {
    Main
} catch {
    Write-Error "安装失败: $_"
    Write-Host $_.ScriptStackTrace
    exit 1
}
