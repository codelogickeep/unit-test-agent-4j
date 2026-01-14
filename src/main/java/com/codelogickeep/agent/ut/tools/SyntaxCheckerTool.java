package com.codelogickeep.agent.ut.tools;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 语法检查工具 - 在编译前使用 JavaParser 进行语法验证
 * 
 * 功能：
 * - 检查 Java 语法是否正确
 * - 验证 import 语句是否完整
 * - 检查常见的引用错误
 * - 提供详细的错误信息和修复建议
 * 
 * 当 LSP 启用且正常时，自动委托给 LspSyntaxCheckerTool 进行更完整的语义检查
 */
@Slf4j
public class SyntaxCheckerTool implements AgentTool {

    private String projectRoot;
    private final JavaParser javaParser;
    
    // LSP 委托：当 LSP 启用且正常时，自动使用 LSP 进行检查
    private LspSyntaxCheckerTool lspDelegate;
    private boolean lspEnabled = false;

    public SyntaxCheckerTool() {
        this.javaParser = new JavaParser();
    }

    public void setProjectRoot(String projectRoot) {
        this.projectRoot = projectRoot;
    }
    
    /**
     * 设置 LSP 委托。当 LSP 可用时，checkSyntax 会自动使用 LSP 进行检查。
     */
    public void setLspDelegate(LspSyntaxCheckerTool lspTool, boolean enabled) {
        this.lspDelegate = lspTool;
        this.lspEnabled = enabled;
        if (enabled && lspTool != null) {
            log.info("SyntaxCheckerTool: LSP delegate enabled, will use LSP for syntax checks");
        }
    }

    @Tool("Check Java file syntax before compilation. Returns syntax errors and suggestions if any.")
    public String checkSyntax(
            @P("Path to the Java file to check") String filePath) {
        
        log.info("Tool Input - checkSyntax: path={}", filePath);
        
        // 当 LSP 启用且正常时，自动委托给 LSP 进行更完整的语义检查
        if (lspEnabled && lspDelegate != null) {
            log.info("Delegating to LSP for syntax check (LSP enabled)");
            return lspDelegate.checkSyntaxWithLsp(filePath);
        }
        
        Path path = resolvePath(filePath);
        if (!Files.exists(path)) {
            String result = "ERROR: File not found: " + filePath;
            log.info("Tool Output - checkSyntax: {}", result);
            return result;
        }

        try {
            String content = Files.readString(path);
            return checkSyntaxContentInternal(content, filePath);
        } catch (IOException e) {
            String result = "ERROR: Failed to read file: " + e.getMessage();
            log.info("Tool Output - checkSyntax: {}", result);
            return result;
        }
    }

    @Tool("Check Java code syntax from content string (useful for validating before writing)")
    public String checkSyntaxContent(
            @P("Java source code content to check") String content,
            @P("File name for error reporting") String fileName) {
        
        log.info("Tool Input - checkSyntaxContent: fileName={}, contentLength={}", fileName, content.length());
        
        // 当 LSP 启用且正常时，自动委托给 LSP 进行更完整的语义检查
        if (lspEnabled && lspDelegate != null) {
            log.info("Delegating to LSP for content syntax check (LSP enabled)");
            return lspDelegate.checkContentWithLsp(content, fileName);
        }
        
        return checkSyntaxContentInternal(content, fileName);
    }
    
    /**
     * 内部方法：使用 JavaParser 进行语法检查
     */
    private String checkSyntaxContentInternal(String content, String fileName) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        // 1. JavaParser 语法检查
        ParseResult<CompilationUnit> parseResult = javaParser.parse(content);
        
        if (!parseResult.isSuccessful()) {
            for (Problem problem : parseResult.getProblems()) {
                String location = problem.getLocation()
                        .map(loc -> "Line " + loc.getBegin().getRange()
                                .map(r -> String.valueOf(r.begin.line))
                                .orElse("?"))
                        .orElse("Unknown location");
                errors.add(String.format("[SYNTAX] %s: %s", location, problem.getMessage()));
            }
        }
        
        // 2. 如果解析成功，进行更深入的检查
        if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
            CompilationUnit cu = parseResult.getResult().get();
            
            // 收集所有导入的类型
            Set<String> importedTypes = new HashSet<>();
            
            // 获取所有 imports
            for (ImportDeclaration imp : cu.getImports()) {
                String importName = imp.getNameAsString();
                if (imp.isAsterisk()) {
                    // 通配符 import，记录包名
                    importedTypes.add(importName + ".*");
                } else {
                    // 获取简单类名
                    String simpleName = importName.contains(".") 
                            ? importName.substring(importName.lastIndexOf('.') + 1) 
                            : importName;
                    importedTypes.add(simpleName);
                }
            }
            
            // 检查常见的 JUnit/Mockito imports
            checkTestImports(cu, importedTypes, warnings);
            
            // 检查类声明
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                // 检查 @Test 注解
                classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                    boolean hasTestAnnotation = method.getAnnotations().stream()
                            .anyMatch(a -> a.getNameAsString().equals("Test"));
                    
                    if (hasTestAnnotation && !importedTypes.contains("Test") 
                            && !hasWildcardImport(importedTypes, "org.junit.jupiter.api")) {
                        warnings.add("[IMPORT] Missing import for @Test: add 'import org.junit.jupiter.api.Test;'");
                    }
                });
                
                // 检查 @Mock 注解
                classDecl.getFields().forEach(field -> {
                    boolean hasMockAnnotation = field.getAnnotations().stream()
                            .anyMatch(a -> a.getNameAsString().equals("Mock"));
                    
                    if (hasMockAnnotation && !importedTypes.contains("Mock")
                            && !hasWildcardImport(importedTypes, "org.mockito")) {
                        warnings.add("[IMPORT] Missing import for @Mock: add 'import org.mockito.Mock;'");
                    }
                });
            });
        }
        
        // 3. 构建结果
        StringBuilder result = new StringBuilder();
        
        if (errors.isEmpty() && warnings.isEmpty()) {
            result.append("SYNTAX_OK: No syntax errors found in ").append(fileName);
        } else {
            if (!errors.isEmpty()) {
                result.append("SYNTAX_ERRORS (").append(errors.size()).append("):\n");
                errors.forEach(e -> result.append("  ").append(e).append("\n"));
            }
            
            if (!warnings.isEmpty()) {
                if (!errors.isEmpty()) result.append("\n");
                result.append("WARNINGS (").append(warnings.size()).append("):\n");
                warnings.forEach(w -> result.append("  ").append(w).append("\n"));
            }
            
            // 添加修复建议
            result.append("\nSUGGESTIONS:\n");
            if (errors.stream().anyMatch(e -> e.contains("';' expected"))) {
                result.append("  - Check for missing semicolons at statement ends\n");
            }
            if (errors.stream().anyMatch(e -> e.contains("'{' expected") || e.contains("'}' expected"))) {
                result.append("  - Check for unbalanced braces\n");
            }
            if (!warnings.isEmpty()) {
                result.append("  - Add missing import statements\n");
            }
        }
        
        String resultStr = result.toString().trim();
        log.info("Tool Output - checkSyntaxContent: {}", 
                resultStr.length() > 100 ? resultStr.substring(0, 100) + "..." : resultStr);
        return resultStr;
    }

    @Tool("Validate test file structure and common patterns")
    public String validateTestStructure(
            @P("Path to the test Java file") String filePath) {
        
        log.info("Tool Input - validateTestStructure: path={}", filePath);
        
        Path path = resolvePath(filePath);
        if (!Files.exists(path)) {
            return "ERROR: File not found: " + filePath;
        }

        try {
            String content = Files.readString(path);
            ParseResult<CompilationUnit> parseResult = javaParser.parse(content);
            
            if (!parseResult.isSuccessful()) {
                return "ERROR: Cannot parse file. Fix syntax errors first using checkSyntax.";
            }
            
            CompilationUnit cu = parseResult.getResult().get();
            List<String> issues = new ArrayList<>();
            
            // 检查测试类结构
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                String className = classDecl.getNameAsString();
                
                // 检查是否有 @ExtendWith(MockitoExtension.class)
                boolean hasExtendWith = classDecl.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("ExtendWith"));
                
                if (!hasExtendWith && classDecl.getFields().stream()
                        .anyMatch(f -> f.getAnnotations().stream()
                                .anyMatch(a -> a.getNameAsString().equals("Mock")))) {
                    issues.add("[STRUCTURE] Class " + className + " uses @Mock but missing @ExtendWith(MockitoExtension.class)");
                }
                
                // 检查测试方法
                long testMethodCount = classDecl.findAll(MethodDeclaration.class).stream()
                        .filter(m -> m.getAnnotations().stream()
                                .anyMatch(a -> a.getNameAsString().equals("Test")))
                        .count();
                
                if (testMethodCount == 0) {
                    issues.add("[STRUCTURE] Class " + className + " has no @Test methods");
                }
            });
            
            if (issues.isEmpty()) {
                return "STRUCTURE_OK: Test structure is valid";
            } else {
                return "STRUCTURE_ISSUES:\n" + issues.stream()
                        .map(i -> "  " + i)
                        .collect(Collectors.joining("\n"));
            }
            
        } catch (IOException e) {
            return "ERROR: Failed to read file: " + e.getMessage();
        }
    }

    private void checkTestImports(CompilationUnit cu, Set<String> importedTypes, List<String> warnings) {
        // 检查常见的测试框架 imports
        Set<String> requiredForAnnotations = new HashSet<>();
        
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            classDecl.getAnnotations().forEach(ann -> {
                String name = ann.getNameAsString();
                switch (name) {
                    case "ExtendWith" -> requiredForAnnotations.add("org.junit.jupiter.api.extension.ExtendWith");
                    case "DisplayName" -> requiredForAnnotations.add("org.junit.jupiter.api.DisplayName");
                    case "Nested" -> requiredForAnnotations.add("org.junit.jupiter.api.Nested");
                }
            });
            
            classDecl.getFields().forEach(field -> {
                field.getAnnotations().forEach(ann -> {
                    String name = ann.getNameAsString();
                    switch (name) {
                        case "Mock" -> requiredForAnnotations.add("org.mockito.Mock");
                        case "InjectMocks" -> requiredForAnnotations.add("org.mockito.InjectMocks");
                        case "Spy" -> requiredForAnnotations.add("org.mockito.Spy");
                        case "Captor" -> requiredForAnnotations.add("org.mockito.Captor");
                    }
                });
            });
            
            classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                method.getAnnotations().forEach(ann -> {
                    String name = ann.getNameAsString();
                    switch (name) {
                        case "Test" -> requiredForAnnotations.add("org.junit.jupiter.api.Test");
                        case "BeforeEach" -> requiredForAnnotations.add("org.junit.jupiter.api.BeforeEach");
                        case "AfterEach" -> requiredForAnnotations.add("org.junit.jupiter.api.AfterEach");
                        case "BeforeAll" -> requiredForAnnotations.add("org.junit.jupiter.api.BeforeAll");
                        case "AfterAll" -> requiredForAnnotations.add("org.junit.jupiter.api.AfterAll");
                    }
                });
            });
        });
        
        // 这里不直接报错，因为可能有通配符 import
        // 只记录为 debug 信息
        log.debug("Required annotations detected: {}", requiredForAnnotations);
    }

    private boolean hasWildcardImport(Set<String> importedTypes, String packagePrefix) {
        return importedTypes.stream().anyMatch(i -> i.startsWith(packagePrefix) && i.endsWith(".*"));
    }

    private Path resolvePath(String filePath) {
        Path path = Paths.get(filePath);
        if (!path.isAbsolute() && projectRoot != null) {
            path = Paths.get(projectRoot, filePath);
        }
        return path;
    }
}
