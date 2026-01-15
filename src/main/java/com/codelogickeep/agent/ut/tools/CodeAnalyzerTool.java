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
import java.util.*;
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

    /**
     * Method priority classification
     */
    public enum MethodPriority {
        P0_CORE("P0 (Core Functions)"),
        P1_STANDARD("P1 (Standard Methods)"),
        P2_LOW("P2 (Low Priority)");

        private final String displayName;

        MethodPriority(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Method info for prioritization
     */
    public static class MethodInfo {
        private final String name;
        private final String signature;
        private final String returnType;
        private final int complexity;
        private final boolean isPublic;
        private final boolean isProtected;
        private final int dependencyCount;
        private final int calledByCount;
        private final boolean isGetter;
        private final boolean isSetter;
        private final boolean isConstructor;
        private MethodPriority priority;

        public MethodInfo(String name, String signature, String returnType, int complexity,
                          boolean isPublic, boolean isProtected, int dependencyCount, int calledByCount,
                          boolean isGetter, boolean isSetter, boolean isConstructor) {
            this.name = name;
            this.signature = signature;
            this.returnType = returnType;
            this.complexity = complexity;
            this.isPublic = isPublic;
            this.isProtected = isProtected;
            this.dependencyCount = dependencyCount;
            this.calledByCount = calledByCount;
            this.isGetter = isGetter;
            this.isSetter = isSetter;
            this.isConstructor = isConstructor;
            this.priority = calculatePriority();
        }

        private MethodPriority calculatePriority() {
            // P2: Getters, setters, simple constructors
            if (isGetter || isSetter) {
                return MethodPriority.P2_LOW;
            }
            if (isConstructor && complexity <= 2) {
                return MethodPriority.P2_LOW;
            }

            // P0: Core functions
            if (complexity >= 5) {
                return MethodPriority.P0_CORE;
            }
            if (isPublic && containsCoreKeyword(name)) {
                return MethodPriority.P0_CORE;
            }
            if (calledByCount >= 3) {
                return MethodPriority.P0_CORE;
            }
            if (isPublic && dependencyCount >= 3) {
                return MethodPriority.P0_CORE;
            }

            // P1: Standard methods
            if (complexity >= 3 || isPublic || isProtected) {
                return MethodPriority.P1_STANDARD;
            }

            return MethodPriority.P2_LOW;
        }

        private boolean containsCoreKeyword(String methodName) {
            String lower = methodName.toLowerCase();
            return lower.startsWith("process") || lower.startsWith("calculate") ||
                   lower.startsWith("validate") || lower.startsWith("execute") ||
                   lower.startsWith("handle") || lower.startsWith("create") ||
                   lower.startsWith("update") || lower.startsWith("delete") ||
                   lower.startsWith("save") || lower.startsWith("load") ||
                   lower.startsWith("parse") || lower.startsWith("convert") ||
                   lower.startsWith("build") || lower.startsWith("init");
        }

        public String getName() { return name; }
        public String getSignature() { return signature; }
        public MethodPriority getPriority() { return priority; }
        public int getComplexity() { return complexity; }
        public int getDependencyCount() { return dependencyCount; }
        public boolean isPublic() { return isPublic; }
        public boolean isConstructor() { return isConstructor; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(signature).append(" : ").append(returnType);
            sb.append(" [complexity:").append(complexity);
            if (isPublic) sb.append(", public");
            else if (isProtected) sb.append(", protected");
            if (dependencyCount > 0) sb.append(", deps:").append(dependencyCount);
            if (calledByCount > 0) sb.append(", callers:").append(calledByCount);
            if (isGetter) sb.append(", getter");
            if (isSetter) sb.append(", setter");
            sb.append("]");
            return sb.toString();
        }
    }

    @Tool("Analyze methods and return prioritized list for testing, identifying core functions")
    public String getPriorityMethods(@P("Path to the Java source file") String path) throws IOException {
        log.info("Tool Input - getPriorityMethods: path={}", path);
        Path sourcePath = Paths.get(path);
        CompilationUnit cu = StaticJavaParser.parse(sourcePath);

        // Build call graph to count how many times each method is called
        Map<String, Integer> callCounts = new HashMap<>();
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                super.visit(n, arg);
                String methodName = n.getNameAsString();
                callCounts.merge(methodName, 1, Integer::sum);
            }
        }, null);

        List<MethodInfo> methods = new ArrayList<>();

        cu.findFirst(ClassOrInterfaceDeclaration.class).ifPresent(clazz -> {
            // Analyze constructors
            for (ConstructorDeclaration ctor : clazz.getConstructors()) {
                int complexity = calculateConstructorComplexity(ctor);
                String params = ctor.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .collect(Collectors.joining(", "));
                String signature = "constructor(" + params + ")";

                int depCount = countMethodCalls(ctor);

                methods.add(new MethodInfo(
                        "<init>", signature, "void", complexity,
                        ctor.isPublic(), ctor.isProtected(),
                        depCount, 0, false, false, true
                ));
            }

            // Analyze methods
            for (MethodDeclaration method : clazz.getMethods()) {
                if (method.isPrivate()) continue; // Skip private methods

                String methodName = method.getNameAsString();
                int complexity = calculateComplexity(method);
                String params = method.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .collect(Collectors.joining(", "));
                String signature = methodName + "(" + params + ")";
                String returnType = method.getType().asString();

                int depCount = countMethodCalls(method);
                int calledBy = callCounts.getOrDefault(methodName, 0);

                boolean isGetter = isGetterMethod(method);
                boolean isSetter = isSetterMethod(method);

                methods.add(new MethodInfo(
                        methodName, signature, returnType, complexity,
                        method.isPublic(), method.isProtected(),
                        depCount, calledBy, isGetter, isSetter, false
                ));
            }
        });

        // Group by priority
        Map<MethodPriority, List<MethodInfo>> grouped = new EnumMap<>(MethodPriority.class);
        for (MethodPriority p : MethodPriority.values()) {
            grouped.put(p, new ArrayList<>());
        }
        for (MethodInfo m : methods) {
            grouped.get(m.getPriority()).add(m);
        }

        // Sort within each group by complexity (descending)
        for (List<MethodInfo> list : grouped.values()) {
            list.sort((a, b) -> Integer.compare(b.getComplexity(), a.getComplexity()));
        }

        // Build output
        StringBuilder result = new StringBuilder();
        result.append("Priority Methods for Testing:\n\n");

        for (MethodPriority priority : MethodPriority.values()) {
            List<MethodInfo> list = grouped.get(priority);
            if (!list.isEmpty()) {
                result.append(priority.getDisplayName()).append(":\n");
                int idx = 1;
                for (MethodInfo m : list) {
                    result.append(String.format("  %d. %s\n", idx++, m.toString()));
                }
                result.append("\n");
            }
        }

        // Recommended order: P0 first, then P1, skip P2
        result.append("Recommended Test Order: ");
        List<String> order = new ArrayList<>();
        for (MethodInfo m : grouped.get(MethodPriority.P0_CORE)) {
            order.add(m.getName());
        }
        for (MethodInfo m : grouped.get(MethodPriority.P1_STANDARD)) {
            order.add(m.getName());
        }
        if (order.isEmpty()) {
            for (MethodInfo m : grouped.get(MethodPriority.P2_LOW)) {
                order.add(m.getName());
            }
        }
        result.append(String.join(" → ", order));
        result.append("\n\nTotal: ").append(methods.size()).append(" methods (");
        result.append("P0:").append(grouped.get(MethodPriority.P0_CORE).size());
        result.append(", P1:").append(grouped.get(MethodPriority.P1_STANDARD).size());
        result.append(", P2:").append(grouped.get(MethodPriority.P2_LOW).size());
        result.append(")");

        String finalResult = result.toString();
        log.info("Tool Output - getPriorityMethods: {} methods analyzed", methods.size());
        return finalResult;
    }

    /**
     * Get prioritized method list as structured data (for iteration)
     */
    public List<MethodInfo> getPriorityMethodsList(String path) throws IOException {
        Path sourcePath = Paths.get(path);
        CompilationUnit cu = StaticJavaParser.parse(sourcePath);

        Map<String, Integer> callCounts = new HashMap<>();
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                super.visit(n, arg);
                callCounts.merge(n.getNameAsString(), 1, Integer::sum);
            }
        }, null);

        List<MethodInfo> methods = new ArrayList<>();

        cu.findFirst(ClassOrInterfaceDeclaration.class).ifPresent(clazz -> {
            for (ConstructorDeclaration ctor : clazz.getConstructors()) {
                int complexity = calculateConstructorComplexity(ctor);
                String params = ctor.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .collect(Collectors.joining(", "));
                methods.add(new MethodInfo(
                        "<init>", "constructor(" + params + ")", "void", complexity,
                        ctor.isPublic(), ctor.isProtected(),
                        countMethodCalls(ctor), 0, false, false, true
                ));
            }

            for (MethodDeclaration method : clazz.getMethods()) {
                if (method.isPrivate()) continue;
                String methodName = method.getNameAsString();
                int complexity = calculateComplexity(method);
                String params = method.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .collect(Collectors.joining(", "));

                methods.add(new MethodInfo(
                        methodName, methodName + "(" + params + ")", method.getType().asString(),
                        complexity, method.isPublic(), method.isProtected(),
                        countMethodCalls(method), callCounts.getOrDefault(methodName, 0),
                        isGetterMethod(method), isSetterMethod(method), false
                ));
            }
        });

        // Sort: P0 first (by complexity desc), then P1, then P2
        methods.sort((a, b) -> {
            int priorityCompare = a.getPriority().ordinal() - b.getPriority().ordinal();
            if (priorityCompare != 0) return priorityCompare;
            return Integer.compare(b.getComplexity(), a.getComplexity());
        });

        return methods;
    }

    private int countMethodCalls(ConstructorDeclaration ctor) {
        AtomicInteger count = new AtomicInteger(0);
        ctor.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                super.visit(n, arg);
                count.incrementAndGet();
            }
        }, null);
        return count.get();
    }

    private int countMethodCalls(MethodDeclaration method) {
        AtomicInteger count = new AtomicInteger(0);
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                super.visit(n, arg);
                count.incrementAndGet();
            }
        }, null);
        return count.get();
    }

    private boolean isGetterMethod(MethodDeclaration method) {
        String name = method.getNameAsString();
        if ((name.startsWith("get") || name.startsWith("is")) && method.getParameters().isEmpty()) {
            // Check if body is simple return
            return method.getBody()
                    .map(body -> body.getStatements().size() == 1)
                    .orElse(false);
        }
        return false;
    }

    private boolean isSetterMethod(MethodDeclaration method) {
        String name = method.getNameAsString();
        if (name.startsWith("set") && method.getParameters().size() == 1
                && "void".equals(method.getType().asString())) {
            return method.getBody()
                    .map(body -> body.getStatements().size() == 1)
                    .orElse(false);
        }
        return false;
    }
}
