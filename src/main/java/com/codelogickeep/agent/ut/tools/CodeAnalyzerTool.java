package com.codelogickeep.agent.ut.tools;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
        log.debug("Tool Input - analyzeClass: path={}", path);
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

        String finalResult = result.toString();
        log.debug("Tool Output - analyzeClass: length={}", finalResult.length());
        return finalResult;
    }

    @Tool("Analyze a specific method with complexity metrics and dependencies")
    public String analyzeMethod(
            @P("Path to the Java source file") String path,
            @P("Method name to analyze") String methodName
    ) throws IOException {
        log.info("Analyzing method: {} in {}", methodName, path);
        log.debug("Tool Input - analyzeMethod: path={}, methodName={}", path, methodName);
        Path sourcePath = Paths.get(path);
        CompilationUnit cu = StaticJavaParser.parse(sourcePath);

        StringBuilder result = new StringBuilder();

        cu.findFirst(ClassOrInterfaceDeclaration.class).ifPresent(clazz -> {
            for (MethodDeclaration method : clazz.getMethods()) {
                if (method.getNameAsString().equals(methodName)) {
                    result.append("Method: ").append(method.getSignature().asString()).append("\n");
                    result.append("Return Type: ").append(method.getType().asString()).append("\n");
                    result.append("Modifiers: ").append(method.getModifiers()).append("\n");

                    // Calculate cyclomatic complexity
                    int complexity = calculateComplexity(method);
                    result.append("Cyclomatic Complexity: ").append(complexity).append("\n");

                    // Find method calls (dependencies)
                    List<String> methodCalls = new ArrayList<>();
                    method.accept(new VoidVisitorAdapter<Void>() {
                        @Override
                        public void visit(MethodCallExpr n, Void arg) {
                            super.visit(n, arg);
                            String scope = n.getScope().map(Object::toString).orElse("this");
                            methodCalls.add(scope + "." + n.getNameAsString() + "()");
                        }
                    }, null);

                    if (!methodCalls.isEmpty()) {
                        result.append("Method Calls:\n");
                        methodCalls.stream().distinct().forEach(call ->
                                result.append("  - ").append(call).append("\n"));
                    }

                    // Get method body line count
                    method.getBody().ifPresent(body -> {
                        int lines = body.toString().split("\n").length;
                        result.append("Body Lines: ").append(lines).append("\n");
                    });

                    break;
                }
            }
        });

        if (result.length() == 0) {
            String errorMsg = "Method not found: " + methodName;
            log.info("Tool Output - analyzeMethod: {}", errorMsg);
            return errorMsg;
        }

        String finalResult = result.toString();
        log.info("Tool Output - analyzeMethod: length={}", finalResult.length());
        return finalResult;
    }

    @Tool("Get compact method list with complexity for test prioritization")
    public String getMethodsForTesting(@P("Path to the Java source file") String path) throws IOException {
        log.info("Tool Input - getMethodsForTesting: path={}", path);
        Path sourcePath = Paths.get(path);
        CompilationUnit cu = StaticJavaParser.parse(sourcePath);

        StringBuilder result = new StringBuilder();
        AtomicInteger methodCount = new AtomicInteger(0);

        cu.findFirst(ClassOrInterfaceDeclaration.class).ifPresent(clazz -> {
            String className = clazz.getNameAsString();
            result.append("Class: ").append(className).append("\n");
            result.append("Methods requiring tests:\n");

            // Include constructors
            for (ConstructorDeclaration ctor : clazz.getConstructors()) {
                int complexity = calculateConstructorComplexity(ctor);
                String params = ctor.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .collect(Collectors.joining(", "));
                result.append(String.format("  - constructor(%s) [complexity: %d]\n", params, complexity));
                methodCount.incrementAndGet();
            }

            // Include public methods
            for (MethodDeclaration method : clazz.getMethods()) {
                if (method.isPublic() || method.isProtected()) {
                    int complexity = calculateComplexity(method);
                    String params = method.getParameters().stream()
                            .map(p -> p.getType().asString())
                            .collect(Collectors.joining(", "));
                    result.append(String.format("  - %s(%s) : %s [complexity: %d]\n",
                            method.getNameAsString(), params,
                            method.getType().asString(), complexity));
                    methodCount.incrementAndGet();
                }
            }
        });

        result.append("Total: ").append(methodCount.get()).append(" methods\n");
        String finalResult = result.toString();
        log.info("Tool Output - getMethodsForTesting: length={}", finalResult.length());
        return finalResult;
    }

    private int calculateComplexity(MethodDeclaration method) {
        AtomicInteger complexity = new AtomicInteger(1); // Base complexity

        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(IfStmt n, Void arg) {
                super.visit(n, arg);
                complexity.incrementAndGet();
            }

            @Override
            public void visit(ForStmt n, Void arg) {
                super.visit(n, arg);
                complexity.incrementAndGet();
            }

            @Override
            public void visit(ForEachStmt n, Void arg) {
                super.visit(n, arg);
                complexity.incrementAndGet();
            }

            @Override
            public void visit(WhileStmt n, Void arg) {
                super.visit(n, arg);
                complexity.incrementAndGet();
            }

            @Override
            public void visit(DoStmt n, Void arg) {
                super.visit(n, arg);
                complexity.incrementAndGet();
            }

            @Override
            public void visit(SwitchEntry n, Void arg) {
                super.visit(n, arg);
                if (!n.getLabels().isEmpty()) {
                    complexity.incrementAndGet();
                }
            }

            @Override
            public void visit(CatchClause n, Void arg) {
                super.visit(n, arg);
                complexity.incrementAndGet();
            }
        }, null);

        return complexity.get();
    }

    private int calculateConstructorComplexity(ConstructorDeclaration ctor) {
        AtomicInteger complexity = new AtomicInteger(1);

        ctor.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(IfStmt n, Void arg) {
                super.visit(n, arg);
                complexity.incrementAndGet();
            }

            @Override
            public void visit(ForStmt n, Void arg) {
                super.visit(n, arg);
                complexity.incrementAndGet();
            }

            @Override
            public void visit(ForEachStmt n, Void arg) {
                super.visit(n, arg);
                complexity.incrementAndGet();
            }
        }, null);

        return complexity.get();
    }
}
