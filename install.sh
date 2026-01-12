#!/usr/bin/env bash
#
# Unit Test Agent 4J - 一键安装脚本 (Linux/macOS)
#
# 使用方法:
#   curl -sSL https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/install.sh | bash
#   或者
#   wget -qO- https://raw.githubusercontent.com/codelogickeep/unit-test-agent-4j/main/install.sh | bash
#

set -e

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 配置
REPO="codelogickeep/unit-test-agent-4j"
VERSION="${VERSION:-latest}"
INSTALL_DIR="$HOME/.utagent"

echo_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

echo_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# 获取下载 URL
get_download_url() {
    if [ "$VERSION" = "latest" ]; then
        LATEST_VERSION=$(curl -s https://api.github.com/repos/$REPO/releases/latest | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/')
        if [ -z "$LATEST_VERSION" ]; then
            echo "错误: 无法获取最新版本信息" >&2
            exit 1
        fi
        echo_info "最新版本: $LATEST_VERSION"
        VERSION=$LATEST_VERSION
    fi

    DOWNLOAD_URL="https://github.com/$REPO/releases/download/$VERSION/utagent.jar"
    echo_info "下载地址: $DOWNLOAD_URL"
}

# 创建安装目录
mkdir -p "$INSTALL_DIR"

# 下载 JAR 文件
echo_info "正在下载..."
curl -L -o "$INSTALL_DIR/utagent.jar" "$DOWNLOAD_URL"
echo_info "下载完成"

# 创建配置文件
if [ ! -f "$INSTALL_DIR/agent.yml" ]; then
    echo_info "创建默认配置文件"
    cat > "$INSTALL_DIR/agent.yml" << 'EOF'
# Unit Test Agent 4J 配置文件
llm:
  protocol: openai
  apiKey: ${env:OPENAI_API_KEY}
  model: gpt-4
EOF
fi

# 创建命令脚本
cat > "$INSTALL_DIR/utagent" << EOF
#!/usr/bin/env bash
java -jar "$INSTALL_DIR/utagent.jar" "\$@"
EOF
chmod +x "$INSTALL_DIR/utagent"

# 显示安装完成信息
echo ""
echo_info "════════════════════════════════════════════════════════════"
echo_info "  Unit Test Agent 4J 安装完成！"
echo_info "════════════════════════════════════════════════════════════"
echo ""
echo "  安装位置: $INSTALL_DIR"
echo "  配置文件: $INSTALL_DIR/agent.yml"
echo ""
echo "  配置 PATH:"
echo "    export PATH=\"\$PATH:$INSTALL_DIR\""
echo ""
echo "  快速开始:"
echo "    export OPENAI_API_KEY=\"your-api-key\""
echo "    utagent --target path/to/Class.java"
echo ""
echo_info "════════════════════════════════════════════════════════════"
