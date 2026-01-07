package com.codelogickeep.agent.ut.tools;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodeAnalyzerTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(CodeAnalyzerTool.class);

    @Tool("Analyze a Java class structure to understand methods and fields")
    public String analyzeClass(@P("Path to the Java source file") String path) throws IOException {
        log.info("Analyzing class structure: {}", path);
        Path sourcePath = Paths.get(path);
        CompilationUnit cu = StaticJavaParser.parse(sourcePath);

        StringBuilder result = new StringBuilder();

        cu.getPackageDeclaration()
                .ifPresent(pd -> result.append("Package: ").append(pd.getNameAsString()).append("\n"));

        // Find the primary class (assuming public class matches filename usually, or
        // just take first)
        cu.findFirst(ClassOrInterfaceDeclaration.class).ifPresent(clazz -> {
            result.append("Class: ").append(clazz.getNameAsString()).append("\n");

            result.append("Dependencies (Fields):\n");
            for (FieldDeclaration field : clazz.getFields()) {
                field.getVariables().forEach(v -> {
                    result.append("  - Type: ").append(v.getTypeAsString())
                            .append(", Name: ").append(v.getNameAsString()).append("\n");
                });
            }

            result.append("Public Methods:\n");
            for (MethodDeclaration method : clazz.getMethods()) {
                if (method.isPublic()) {
                    result.append("  - Signature: ").append(method.getSignature().asString()).append("\n");
                    result.append("    ReturnType: ").append(method.getType().asString()).append("\n");
                    if (method.getParameters().isNonEmpty()) {
                        result.append("    Parameters: ").append(
                                method.getParameters().stream()
                                        .map(Parameter::toString)
                                        .collect(Collectors.joining(", ")))
                                .append("\n");
                    }
                }
            }
        });

        return result.toString();
    }
}
