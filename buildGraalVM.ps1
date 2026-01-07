# ==========================================
# 🛠 配置区域
# ==========================================
# 1. GraalVM 路径 (保持你原来的)
$GraalVMPath = "D:\ProjectSoftware\graalvm-jdk-21.0.6" 

# 2. [TODO] 请修改这里：Visual Studio 启动脚本路径
# 通常位于: C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat
# 注意：如果你的安装目录是 2026，请相应修改路径中的年份
$VsBatPath = "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat"

$ConfigDir = "src\main\resources\META-INF\native-image"
# ==========================================

$ErrorActionPreference = "Stop"

function Write-Step { param([string]$Message) Write-Host "`n➤ $Message" -ForegroundColor Yellow }
function Write-ErrorMsg { param([string]$Message) Write-Host "`n❌ $Message" -ForegroundColor Red }
function Write-Success { param([string]$Message) Write-Host "`n✅ $Message" -ForegroundColor Green }

# --- 检查环境 ---

# 1. 检查 GraalVM
if (-not (Test-Path "$GraalVMPath\bin\java.exe")) {
    Write-ErrorMsg "未找到 GraalVM，请检查脚本顶部的 `$GraalVMPath 变量。"
    exit 1
}

# 2. 检查 Visual Studio 脚本 (新增检查)
if (-not (Test-Path $VsBatPath)) {
    Write-ErrorMsg "未找到 Visual Studio 启动脚本 (vcvars64.bat)。"
    Write-Host "请检查脚本顶部的 `$VsBatPath 变量路径是否正确。" -ForegroundColor Gray
    exit 1
}

# 3. 切换 Java 环境
$env:JAVA_HOME = $GraalVMPath
$env:Path = "$GraalVMPath\bin;" + $env:Path
Write-Host "Java Version:" -ForegroundColor Gray
java -version

# ==========================================
# [关键修复] 重定向临时目录，防止杀毒软件拦截
# ==========================================
Write-Step "配置构建临时目录..."
$BuildTempDir = "$PWD\target\native_temp"
if (-not (Test-Path $BuildTempDir)) { 
    New-Item -ItemType Directory -Force -Path $BuildTempDir | Out-Null 
}
# 强制修改当前会话的 TEMP 变量，让 GraalVM 把临时文件生成在 target 目录下
$env:TEMP = $BuildTempDir
$env:TMP = $BuildTempDir
Write-Host "已将临时目录重定向至: $BuildTempDir" -ForegroundColor Gray
# ==========================================

# --- 开始构建 ---

# 4. Maven 打包
Write-Step "[Step 1/3] Maven 打包..."
cmd /c mvn clean package -DskipTests
if ($LASTEXITCODE -ne 0) { Write-ErrorMsg "Maven 打包失败"; exit 1 }

# 5. 自动查找生成的 Jar 包
$JarPath = Get-ChildItem -Path "target" -Filter "*.jar" | 
           Where-Object { $_.Name -notmatch "original" -and $_.Name -notmatch "sources" } | 
           Select-Object -First 1 -ExpandProperty FullName

if (-not $JarPath) {
    Write-ErrorMsg "在 target 目录下未找到 Jar 包！"
    exit 1
}
Write-Host "找到 Jar 包: $JarPath" -ForegroundColor Cyan

# 6. Tracing Agent (智能追踪 - 终极修复版)
Write-Step "[Step 2/3] 收集反射配置..."

# 确保配置目录存在
if (-not (Test-Path $ConfigDir)) { New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null }
"public class Dummy {}" | Set-Content Dummy.java

# [关键新增 1] 创建一个临时的 dummy 配置文件
# 只有文件存在，代码才会执行 Jackson 的反序列化逻辑，Agent 才能抓到反射
$DummyConfigContent = @"
llm:
  apiKey: trace-key
  provider: openai
"@
$DummyConfigContent | Set-Content agent.yml

# 启用合并模式 (config-merge-dir)
$AgentArg = "-agentlib:native-image-agent=config-merge-dir=$ConfigDir"

try {
    Write-Host "   [1/2] 追踪主程序 (包含读取配置)..." -ForegroundColor Gray
    # 第一跑：因为有了 agent.yml，这次会触发 AppConfig 的构造函数反射
    java $AgentArg `
         -jar "$JarPath" `
         Dummy.java --dry-run
    
    Write-Host "   [2/2] 追踪 Config 命令 (包含写入配置)..." -ForegroundColor Gray
    # 第二跑：触发序列化反射
    java $AgentArg `
         -jar "$JarPath" `
         config --api-key="trace-key" --model="trace-model"
         
} catch { Write-Warning "Agent 运行捕获结束 (预期内)" }

# [关键新增 2] 清理临时文件
if (Test-Path Dummy.java) { Remove-Item Dummy.java }
if (Test-Path agent.yml) { Remove-Item agent.yml } # 删掉这个假的配置文件

Write-Success "反射配置收集完毕"

# 7. Native 编译 (关键修改：手动挂载 VS 环境)
Write-Step "[Step 3/3] Native 编译 (已挂载 VS 环境)..."

# 构造组合命令: 
# call "路径" -> 激活 C++ 环境
# && -> 成功后执行
# mvn ... -> 开始编译
$BuildCmd = "call `"$VsBatPath`" && mvn -Pnative native:compile -DskipTests"

# 执行组合命令
cmd /c $BuildCmd

if ($LASTEXITCODE -eq 0) {
    Write-Success "构建成功！"
    Write-Host "文件位置: target\utAgent4J.exe" -ForegroundColor Cyan
} else {
    Write-ErrorMsg "编译失败"
    exit 1
}