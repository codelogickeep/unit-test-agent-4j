#!/usr/bin/env bash
#
# Unit Test Agent 4J - 自动构建脚本 (Linux/macOS)
#
# 使用方法:
#   curl -sSL https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/build.sh | bash
#   或者
#   wget -qO- https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/build.sh | bash
#

set -e

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# 配置
REPO="codelogickeep/unit-test-agent-4j"
# 如果未指定版本，自动获取最新 release 版本
# 用户可通过 VERSION=v2.1.0 ./build.sh 指定特定版本
VERSION="${VERSION:-}"
INSTALL_DIR="$HOME/.utagent"
BUILD_DIR="$HOME/.utagent-build"

echo_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

echo_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

echo_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查命令是否存在
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# 检查环境
check_environment() {
    echo_info "检查构建环境..."

    # 检查 Java
    if ! command_exists java; then
        echo_error "未找到 Java。请安装 JDK 21 或更高版本。"
        echo "  macOS: brew install openjdk@21"
        echo "  Ubuntu: sudo apt install openjdk-21-jdk"
        exit 1
    fi

    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 21 ]; then
        echo_error "Java 版本过低。当前版本: $JAVA_VERSION，需要: 21+"
        exit 1
    fi
    echo_info "✓ Java 版本: $(java -version 2>&1 | head -n 1)"

    # 检查 Maven
    if ! command_exists mvn; then
        echo_error "未找到 Maven。请安装 Maven 3.8 或更高版本。"
        echo "  macOS: brew install maven"
        echo "  Ubuntu: sudo apt install maven"
        exit 1
    fi
    echo_info "✓ Maven 版本: $(mvn -version | head -n 1)"

    # 检查 Git
    if ! command_exists git; then
        echo_error "未找到 Git。请安装 Git。"
        echo "  macOS: brew install git"
        echo "  Ubuntu: sudo apt install git"
        exit 1
    fi
    echo_info "✓ Git 版本: $(git --version)"
}

# 获取最新版本
get_latest_version() {
    echo_info "获取最新版本..."
    
    # 尝试从 GitHub API 获取最新 release
    local latest
    latest=$(curl -s "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
    
    if [ -z "$latest" ]; then
        # 如果获取失败，尝试从 git ls-remote 获取最新 tag
        latest=$(git ls-remote --tags --sort=-version:refname "https://github.com/$REPO.git" 2>/dev/null | head -n1 | sed 's/.*refs\/tags\///' | sed 's/\^{}//')
    fi
    
    if [ -z "$latest" ]; then
        # 如果仍然失败，使用默认版本
        latest="main"
        echo_warn "无法获取最新版本，使用 main 分支"
    else
        echo_info "✓ 最新版本: $latest"
    fi
    
    echo "$latest"
}

# 克隆源码
clone_source() {
    echo_info "克隆源码..."

    if [ -d "$BUILD_DIR" ]; then
        echo_warn "构建目录已存在，删除旧版本..."
        rm -rf "$BUILD_DIR"
    fi

    # 如果未指定版本，自动获取最新版本
    if [ -z "$VERSION" ]; then
        VERSION=$(get_latest_version)
    fi

    echo_info "使用版本: $VERSION"
    git clone --depth 1 --branch "$VERSION" "https://github.com/$REPO.git" "$BUILD_DIR"
    cd "$BUILD_DIR"
    echo_info "✓ 源码已克隆到: $BUILD_DIR"
}

# 构建项目
build_project() {
    echo_info "开始构建项目..."
    echo_info "这可能需要 2-5 分钟，请耐心等待..."

    mvn clean package -DskipTests -q

    if [ ! -f "target/utagent.jar" ]; then
        echo_error "构建失败：未找到 utagent.jar"
        exit 1
    fi

    JAR_SIZE=$(du -h "target/utagent.jar" | cut -f1)
    echo_info "✓ 构建成功！JAR 大小: $JAR_SIZE"
}

# 安装
install() {
    echo_info "安装到: $INSTALL_DIR"

    # 创建安装目录
    mkdir -p "$INSTALL_DIR"

    # 复制 JAR
    cp "$BUILD_DIR/target/utagent.jar" "$INSTALL_DIR/"

    # 创建命令脚本
    cat > "$INSTALL_DIR/utagent" << 'EOF'
#!/usr/bin/env bash
java -jar "$HOME/.utagent/utagent.jar" "$@"
EOF
    chmod +x "$INSTALL_DIR/utagent"

    # 创建配置文件
    if [ ! -f "$INSTALL_DIR/agent.yml" ]; then
        cat > "$INSTALL_DIR/agent.yml" << 'CONFIG_EOF'
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
CONFIG_EOF
    fi

    echo_info "✓ 安装完成"
}

# 清理构建文件
cleanup() {
    echo_info "清理构建文件..."
    rm -rf "$BUILD_DIR"
    echo_info "✓ 清理完成"
}

# 显示完成信息
show_completion() {
    echo ""
    echo_info "════════════════════════════════════════════════════════════"
    echo_info "  Unit Test Agent 4J 安装成功！"
    echo_info "════════════════════════════════════════════════════════════"
    echo ""
    echo "  安装位置: $INSTALL_DIR"
    echo "  可执行文件: $INSTALL_DIR/utagent"
    echo "  配置文件: $INSTALL_DIR/agent.yml"
    echo ""
    echo "  下一步操作:"
    echo ""
    echo "  1. 添加到 PATH (添加到 ~/.bashrc 或 ~/.zshrc):"
    echo "     export PATH=\"\$PATH:$INSTALL_DIR\""
    echo ""
    echo "  2. 配置 API Key:"
    echo "     export OPENAI_API_KEY=\"your-api-key\""
    echo ""
    echo "  3. 运行测试生成:"
    echo "     utagent --target path/to/Class.java"
    echo ""
    echo "  或者使用完整路径:"
    echo "     $INSTALL_DIR/utagent --target path/to/Class.java"
    echo ""
    echo_info "════════════════════════════════════════════════════════════"
    echo ""
}

# 主函数
main() {
    echo_info "开始安装 Unit Test Agent 4J (从源码构建)..."
    echo ""

    check_environment
    clone_source
    build_project
    install
    cleanup
    show_completion
}

main
