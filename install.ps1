# Unit Test Agent 4J - 一键安装脚本 (Windows)
#
# 使用方法:
#   irm https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/install.ps1 | iex
#   或者
#   powershell -c "irm https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/install.ps1 | iex"
#

$ErrorActionPreference = "Stop"

# 配置
$REPO = "codelogickeep/unit-test-agent-4j"
$VERSION = if ($env:VERSION) { $env:VERSION } else { "latest" }
$INSTALL_DIR = "$env:USERPROFILE\.utagent"

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

# 获取下载 URL
function Get-DownloadUrl {
    if ($VERSION -eq "latest") {
        $latestVersion = (Invoke-RestMethod "https://api.github.com/repos/$REPO/releases/latest").tag_name
        if (-not $latestVersion) {
            throw "无法获取最新版本信息"
        }
        Write-Info "最新版本: $latestVersion"
        $script:VERSION = $latestVersion
    }

    $script:DOWNLOAD_URL = "https://github.com/$REPO/releases/download/$VERSION/utagent.jar"
    Write-Info "下载地址: $DOWNLOAD_URL"
}

# 创建安装目录
function Install-Directory {
    if (-not (Test-Path $INSTALL_DIR)) {
        New-Item -ItemType Directory -Path $INSTALL_DIR -Force | Out-Null
    }
}

# 下载 JAR 文件
function Install-Jar {
    Write-Info "正在下载..."
    Invoke-WebRequest -Uri $DOWNLOAD_URL -OutFile "$INSTALL_DIR\utagent.jar"
    Write-Info "下载完成"
}

# 创建配置文件
function Install-Config {
    $configPath = "$INSTALL_DIR\agent.yml"
    if (-not (Test-Path $configPath)) {
        Write-Info "创建默认配置文件"
        @"
# Unit Test Agent 4J 配置文件
llm:
  protocol: openai
  apiKey: ${env:OPENAI_API_KEY}
  model: gpt-4
"@ | Out-File -FilePath $configPath -Encoding UTF8
    }
}

# 创建命令脚本
function Install-Wrapper {
    $wrapperPath = "$INSTALL_DIR\utagent.ps1"
    @"
`$scriptPath = Split-Path -Parent `$MyInvocation.MyCommand.Path
java -jar "`$scriptPath\utagent.jar" `$args
"@ | Out-File -FilePath $wrapperPath -Encoding UTF8

    # 创建批处理文件
    $batchPath = "$INSTALL_DIR\utagent.bat"
    @"
@echo off
java -jar "%~dp0utagent.jar" %*
"@ | Out-File -FilePath $batchPath -Encoding ASCII
}

# 添加到 PATH
function Add-ToPath {
    $currentPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($currentPath -notlike "*$INSTALL_DIR*") {
        Write-Warn "请手动将以下路径添加到系统 PATH: $INSTALL_DIR"
        Write-Warn "或在管理员 PowerShell 中运行:"
        Write-Host "  [Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path', 'User') + ';$INSTALL_DIR', 'User')" -ForegroundColor Cyan
    }
}

# 显示完成信息
function Show-Completion {
    Write-Host ""
    Write-Info "════════════════════════════════════════════════════════════"
    Write-Info "  Unit Test Agent 4J 安装完成！"
    Write-Info "════════════════════════════════════════════════════════════"
    Write-Host ""
    Write-Host "  安装位置: $INSTALL_DIR"
    Write-Host "  配置文件: $INSTALL_DIR\agent.yml"
    Write-Host ""
    Write-Host "  快速开始:"
    Write-Host "    `$env:OPENAI_API_KEY = 'your-api-key'"
    Write-Host "    utagent --target path\to\Class.java"
    Write-Host ""
    Write-Info "════════════════════════════════════════════════════════════"
    Write-Host ""
}

# 主函数
function Main {
    Write-Info "开始安装 Unit Test Agent 4J..."
    Write-Host ""

    Get-DownloadUrl
    Install-Directory
    Install-Jar
    Install-Config
    Install-Wrapper
    Add-ToPath
    Show-Completion
}

Main
