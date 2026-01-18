package com.codelogickeep.agent.ut.framework.util;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 类名提取工具 - 从文件路径中提取完整的类名（包含包名）
 */
public class ClassNameExtractor {
    private static final Logger log = LoggerFactory.getLogger(ClassNameExtractor.class);

    /**
     * 从源文件路径提取完整的类名（包含包名）
     *
     * @param sourceFile 源文件路径
     * @return 完整的类名（如 "com.example.Calculator"），如果无法提取则返回 null
     */
    public static String extractClassName(String sourceFile) {
        // 1. 尝试从路径解析 (快速路径)
        String normalized = sourceFile.replace("\\", "/");

        // 处理带前导斜杠和不带前导斜杠的路径
        int srcMainIndex = normalized.indexOf("/src/main/java/");
        if (srcMainIndex < 0) {
            srcMainIndex = normalized.indexOf("src/main/java/");
            if (srcMainIndex >= 0) {
                String className = normalized.substring(srcMainIndex + "src/main/java/".length());
                className = className.replace("/", ".").replace(".java", "");
                return className;
            }
        } else {
            String className = normalized.substring(srcMainIndex + "/src/main/java/".length());
            className = className.replace("/", ".").replace(".java", "");
            return className;
        }

        // 同样处理 src/test/java/
        int srcTestIndex = normalized.indexOf("/src/test/java/");
        if (srcTestIndex < 0) {
            srcTestIndex = normalized.indexOf("src/test/java/");
            if (srcTestIndex >= 0) {
                String className = normalized.substring(srcTestIndex + "src/test/java/".length());
                className = className.replace("/", ".").replace(".java", "");
                return className;
            }
        } else {
            String className = normalized.substring(srcTestIndex + "/src/test/java/".length());
            className = className.replace("/", ".").replace(".java", "");
            return className;
        }

        // 2. 尝试解析文件内容 (回退机制)
        try {
            Path path = Paths.get(sourceFile);
            if (Files.exists(path)) {
                CompilationUnit cu = StaticJavaParser.parse(path);

                // 获取包名
                String packageName = cu.getPackageDeclaration()
                        .map(pd -> pd.getNameAsString())
                        .orElse("");

                // 获取主类名
                String simpleClassName = null;
                List<TypeDeclaration<?>> types = cu.getTypes();

                if (types.isEmpty()) {
                    log.warn("No types found in file: {}", sourceFile);
                    return null;
                }

                // 策略1: 优先查找 public 类
                for (TypeDeclaration<?> type : types) {
                    if (type.isPublic()) {
                        simpleClassName = type.getNameAsString();
                        break;
                    }
                }

                // 策略2: 查找与文件名匹配的类
                if (simpleClassName == null) {
                    String fileName = path.getFileName().toString().replace(".java", "");
                    for (TypeDeclaration<?> type : types) {
                        if (type.getNameAsString().equals(fileName)) {
                            simpleClassName = type.getNameAsString();
                            break;
                        }
                    }
                }

                // 策略3: 取第一个类
                if (simpleClassName == null) {
                    simpleClassName = types.get(0).getNameAsString();
                    log.info("Using first found type '{}' as main class for {}", simpleClassName, sourceFile);
                }

                if (!packageName.isEmpty()) {
                    return packageName + "." + simpleClassName;
                } else {
                    return simpleClassName;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse source file to extract class name: {}", e.getMessage());
        }

        return null;
    }
}
