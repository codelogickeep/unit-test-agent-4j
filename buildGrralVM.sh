#!/bin/bash

# ==========================================
# Unit Test Agent - Native Image 构建脚本 (指定 JDK 版)
# ==========================================

# --- 🛠 配置区域 (请修改这里) ---
# 填入你下载并解压的 Oracle GraalVM 绝对路径
# 例如 Mac: /Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.2+13.1/Contents/Home
# 例如 Linux: /usr/lib/jvm/graalvm-jdk-21.0.2+13.1
GRAALVM_HOME="/Users/yourname/sdks/graalvm-jdk-21.0.2+13.1/Contents/Home"

# 项目配置
APP_NAME="unit-test-agent-4j"
JAR_VERSION="0.1.0-LITE"
JAR_PATH="target/${APP_NAME}-${JAR_VERSION}.jar"
CONFIG_DIR="src/main/resources/META-INF/native-image"
# -----------------------------

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}🚀 初始化构建环境...${NC}"

# 1. 检查 GraalVM 路径是否存在
if [ ! -d "$GRAALVM_HOME" ]; then
    echo -e "${RED}❌ 错误: 未找到 GraalVM 目录: $GRAALVM_HOME${NC}"
    echo "请修改脚本顶部的 GRAALVM_HOME 变量。"
    exit 1
fi

# 2. 关键步骤：临时切换环境变量 (仅对当前脚本生效)
export JAVA_HOME=$GRAALVM_HOME
export PATH=$JAVA_HOME/bin:$PATH

echo -e "当前使用的 Java 版本:"
java -version
if [[ $(java -version 2>&1) != *"GraalVM"* ]]; then
    echo -e "${RED}❌ 警告: 似乎没有成功切换到 GraalVM，请检查路径。${NC}"
    # 这里不强制退出，万一你的 GraalVM 名字显示不一样
fi

# 3. 清理并打包
echo -e "\n${YELLOW}[Step 1/3] Maven 打包 (使用 GraalVM)...${NC}"
mvn clean package -DskipTests
if [ $? -ne 0 ]; then exit 1; fi

# 4. 运行 Tracing Agent
echo -e "\n${YELLOW}[Step 2/3] 运行 Tracing Agent 收集反射配置...${NC}"
mkdir -p $CONFIG_DIR
echo "public class Dummy {}" > Dummy.java

# 使用指定的 java 运行
java -agentlib:native-image-agent=config-output-dir=$CONFIG_DIR \
     -jar $JAR_PATH \
     Dummy.java --dry-run

rm Dummy.java
echo -e "${GREEN}✅ 配置已生成${NC}"

# 5. Native 编译
echo -e "\n${YELLOW}[Step 3/3] Native 编译...${NC}"
# 因为上面 export 了 JAVA_HOME，Maven 插件会自动找到 native-image 命令
mvn -Pnative native:compile -DskipTests

if [ $? -eq 0 ]; then
    echo -e "\n${GREEN}🎉 构建成功！文件位置: target/utAgent4J${NC}"
else
    echo -e "\n${RED}❌ 编译失败${NC}"
    exit 1
fi