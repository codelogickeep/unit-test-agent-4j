package com.codelogickeep.agent.ut.tools;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Builder;
import lombok.Data;
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
 * 边界值分析工具 - 通过 AST 分析识别需要测试的边界条件
 * 
 * 功能：
 * 1. 识别条件分支（if/switch/三元表达式）
 * 2. 提取边界条件（比较运算符、null 检查、集合边界等）
 * 3. 生成边界值测试建议
 */
@Slf4j
public class BoundaryAnalyzerTool implements AgentTool {

    /**
     * 分析类中的所有边界条件
     */
    @Tool("Analyze boundary conditions in a Java class to suggest edge case tests.")
    public BoundaryAnalysisResult analyzeClassBoundaries(
            @P("Path to the Java source file") String filePath) throws IOException {
        log.info("Tool Input - analyzeClassBoundaries: filePath={}", filePath);

        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + filePath);
        }

        CompilationUnit cu = StaticJavaParser.parse(path);
        
        List<BoundaryCondition> conditions = new ArrayList<>();
        List<String> testSuggestions = new ArrayList<>();

        cu.accept(new BoundaryVisitor(conditions), null);

        // 生成测试建议
        for (BoundaryCondition condition : conditions) {
            testSuggestions.addAll(generateTestSuggestions(condition));
        }

        // 去重
        testSuggestions = testSuggestions.stream().distinct().collect(Collectors.toList());

        BoundaryAnalysisResult result = BoundaryAnalysisResult.builder()
                .filePath(filePath)
                .totalConditions(conditions.size())
                .conditions(conditions)
                .testSuggestions(testSuggestions)
                .build();

        log.info("Tool Output - analyzeClassBoundaries: {} conditions, {} suggestions",
                conditions.size(), testSuggestions.size());
        return result;
    }

    /**
     * 分析特定方法的边界条件
     */
    @Tool("Analyze boundary conditions in a specific method.")
    public BoundaryAnalysisResult analyzeMethodBoundaries(
            @P("Path to the Java source file") String filePath,
            @P("Name of the method to analyze") String methodName) throws IOException {
        log.info("Tool Input - analyzeMethodBoundaries: filePath={}, methodName={}", filePath, methodName);

        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + filePath);
        }

        CompilationUnit cu = StaticJavaParser.parse(path);
        
        List<BoundaryCondition> conditions = new ArrayList<>();

        cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .forEach(method -> method.accept(new BoundaryVisitor(conditions), null));

        List<String> testSuggestions = new ArrayList<>();
        for (BoundaryCondition condition : conditions) {
            testSuggestions.addAll(generateTestSuggestions(condition));
        }

        BoundaryAnalysisResult result = BoundaryAnalysisResult.builder()
                .filePath(filePath)
                .methodName(methodName)
                .totalConditions(conditions.size())
                .conditions(conditions)
                .testSuggestions(testSuggestions.stream().distinct().collect(Collectors.toList()))
                .build();

        log.info("Tool Output - analyzeMethodBoundaries: {} conditions", conditions.size());
        return result;
    }

    /**
     * 获取常见边界值测试模板
     */
    @Tool("Get boundary value test templates for common scenarios.")
    public String getBoundaryTestTemplates(
            @P("Type of boundary: numeric, string, collection, null, boolean") String boundaryType) {
        log.info("Tool Input - getBoundaryTestTemplates: boundaryType={}", boundaryType);

        StringBuilder templates = new StringBuilder();
        templates.append("Boundary Test Templates for: ").append(boundaryType).append("\n\n");

        switch (boundaryType.toLowerCase()) {
            case "numeric":
                templates.append(getNumericTemplates());
                break;
            case "string":
                templates.append(getStringTemplates());
                break;
            case "collection":
                templates.append(getCollectionTemplates());
                break;
            case "null":
                templates.append(getNullTemplates());
                break;
            case "boolean":
                templates.append(getBooleanTemplates());
                break;
            default:
                templates.append(getAllTemplates());
        }

        String result = templates.toString();
        log.info("Tool Output - getBoundaryTestTemplates: length={}", result.length());
        return result;
    }

    // ==================== Visitor 实现 ====================

    private static class BoundaryVisitor extends VoidVisitorAdapter<Void> {
        private final List<BoundaryCondition> conditions;
        private String currentMethod = "";

        BoundaryVisitor(List<BoundaryCondition> conditions) {
            this.conditions = conditions;
        }

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            currentMethod = n.getNameAsString();
            super.visit(n, arg);
        }

        @Override
        public void visit(IfStmt n, Void arg) {
            analyzeCondition(n.getCondition().toString(), n.getBegin().map(p -> p.line).orElse(0), "if");
            super.visit(n, arg);
        }

        @Override
        public void visit(SwitchStmt n, Void arg) {
            int line = n.getBegin().map(p -> p.line).orElse(0);
            conditions.add(BoundaryCondition.builder()
                    .methodName(currentMethod)
                    .lineNumber(line)
                    .conditionType(ConditionType.SWITCH)
                    .expression(n.getSelector().toString())
                    .branchCount(n.getEntries().size())
                    .hasDefaultBranch(n.getEntries().stream().anyMatch(e -> e.getLabels().isEmpty()))
                    .build());
            super.visit(n, arg);
        }

        @Override
        public void visit(ForStmt n, Void arg) {
            n.getCompare().ifPresent(compare -> {
                analyzeCondition(compare.toString(), n.getBegin().map(p -> p.line).orElse(0), "for");
            });
            super.visit(n, arg);
        }

        @Override
        public void visit(WhileStmt n, Void arg) {
            analyzeCondition(n.getCondition().toString(), n.getBegin().map(p -> p.line).orElse(0), "while");
            super.visit(n, arg);
        }

        @Override
        public void visit(BinaryExpr n, Void arg) {
            // 检测比较运算符的边界
            if (isComparisonOperator(n.getOperator())) {
                int line = n.getBegin().map(p -> p.line).orElse(0);
                
                BoundaryCondition.BoundaryConditionBuilder builder = BoundaryCondition.builder()
                        .methodName(currentMethod)
                        .lineNumber(line)
                        .expression(n.toString());

                switch (n.getOperator()) {
                    case LESS:
                    case LESS_EQUALS:
                    case GREATER:
                    case GREATER_EQUALS:
                        builder.conditionType(ConditionType.NUMERIC_COMPARISON)
                               .boundaryHint("Test with value-1, value, value+1");
                        break;
                    case EQUALS:
                    case NOT_EQUALS:
                        if (n.toString().contains("null")) {
                            builder.conditionType(ConditionType.NULL_CHECK)
                                   .boundaryHint("Test with null and non-null values");
                        } else {
                            builder.conditionType(ConditionType.EQUALITY)
                                   .boundaryHint("Test with equal and unequal values");
                        }
                        break;
                }
                
                conditions.add(builder.build());
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            String methodName = n.getNameAsString();
            int line = n.getBegin().map(p -> p.line).orElse(0);

            // 检测常见的边界相关方法调用
            if (methodName.equals("isEmpty") || methodName.equals("isBlank")) {
                conditions.add(BoundaryCondition.builder()
                        .methodName(currentMethod)
                        .lineNumber(line)
                        .conditionType(ConditionType.EMPTY_CHECK)
                        .expression(n.toString())
                        .boundaryHint("Test with empty, null, and non-empty values")
                        .build());
            } else if (methodName.equals("size") || methodName.equals("length")) {
                conditions.add(BoundaryCondition.builder()
                        .methodName(currentMethod)
                        .lineNumber(line)
                        .conditionType(ConditionType.SIZE_CHECK)
                        .expression(n.toString())
                        .boundaryHint("Test with 0, 1, and max size")
                        .build());
            } else if (methodName.equals("contains") || methodName.equals("containsKey")) {
                conditions.add(BoundaryCondition.builder()
                        .methodName(currentMethod)
                        .lineNumber(line)
                        .conditionType(ConditionType.CONTAINS_CHECK)
                        .expression(n.toString())
                        .boundaryHint("Test with present and absent elements")
                        .build());
            }
            
            super.visit(n, arg);
        }

        private void analyzeCondition(String condition, int line, String stmtType) {
            BoundaryCondition.BoundaryConditionBuilder builder = BoundaryCondition.builder()
                    .methodName(currentMethod)
                    .lineNumber(line)
                    .expression(condition);

            if (condition.contains("null")) {
                builder.conditionType(ConditionType.NULL_CHECK)
                       .boundaryHint("Test with null and non-null values");
            } else if (condition.contains("&&") || condition.contains("||")) {
                builder.conditionType(ConditionType.COMPOUND)
                       .boundaryHint("Test each sub-condition independently and combined");
            } else if (condition.matches(".*[<>=!].*")) {
                builder.conditionType(ConditionType.NUMERIC_COMPARISON)
                       .boundaryHint("Test boundary values");
            } else {
                builder.conditionType(ConditionType.BOOLEAN)
                       .boundaryHint("Test true and false cases");
            }

            conditions.add(builder.build());
        }

        private boolean isComparisonOperator(BinaryExpr.Operator op) {
            return op == BinaryExpr.Operator.LESS ||
                   op == BinaryExpr.Operator.LESS_EQUALS ||
                   op == BinaryExpr.Operator.GREATER ||
                   op == BinaryExpr.Operator.GREATER_EQUALS ||
                   op == BinaryExpr.Operator.EQUALS ||
                   op == BinaryExpr.Operator.NOT_EQUALS;
        }
    }

    // ==================== 测试建议生成 ====================

    private List<String> generateTestSuggestions(BoundaryCondition condition) {
        List<String> suggestions = new ArrayList<>();
        String method = condition.getMethodName();

        switch (condition.getConditionType()) {
            case NUMERIC_COMPARISON:
                suggestions.add(String.format("Test %s with boundary value (exact match)", method));
                suggestions.add(String.format("Test %s with boundary-1 (just below)", method));
                suggestions.add(String.format("Test %s with boundary+1 (just above)", method));
                suggestions.add(String.format("Test %s with Integer.MIN_VALUE", method));
                suggestions.add(String.format("Test %s with Integer.MAX_VALUE", method));
                break;

            case NULL_CHECK:
                suggestions.add(String.format("Test %s with null input", method));
                suggestions.add(String.format("Test %s with non-null input", method));
                break;

            case EMPTY_CHECK:
                suggestions.add(String.format("Test %s with empty string/collection", method));
                suggestions.add(String.format("Test %s with null", method));
                suggestions.add(String.format("Test %s with single element", method));
                suggestions.add(String.format("Test %s with multiple elements", method));
                break;

            case SIZE_CHECK:
                suggestions.add(String.format("Test %s with size 0", method));
                suggestions.add(String.format("Test %s with size 1", method));
                suggestions.add(String.format("Test %s with large size", method));
                break;

            case SWITCH:
                suggestions.add(String.format("Test %s for each switch case", method));
                if (!condition.isHasDefaultBranch()) {
                    suggestions.add(String.format("WARNING: %s switch has no default case - add test for unexpected values", method));
                }
                break;

            case COMPOUND:
                suggestions.add(String.format("Test %s with all conditions true", method));
                suggestions.add(String.format("Test %s with all conditions false", method));
                suggestions.add(String.format("Test %s with mixed condition states", method));
                break;

            case BOOLEAN:
                suggestions.add(String.format("Test %s with true condition", method));
                suggestions.add(String.format("Test %s with false condition", method));
                break;

            default:
                suggestions.add(String.format("Test %s line %d: %s", method, condition.getLineNumber(), condition.getBoundaryHint()));
        }

        return suggestions;
    }

    // ==================== 模板方法 ====================

    private String getNumericTemplates() {
        return """
            Numeric Boundary Tests:
            
            1. Integer boundaries:
               - Test with 0, -1, 1
               - Test with Integer.MIN_VALUE, Integer.MAX_VALUE
               - Test with boundary value ± 1
               
            2. Comparison operators:
               - For 'x < 10': test with 9, 10, 11
               - For 'x <= 10': test with 9, 10, 11
               - For 'x > 10': test with 9, 10, 11
               - For 'x >= 10': test with 9, 10, 11
               
            3. Division/Modulo:
               - Test division by zero handling
               - Test with negative divisors
               
            Example:
            @ParameterizedTest
            @ValueSource(ints = {-1, 0, 1, Integer.MIN_VALUE, Integer.MAX_VALUE})
            void testWithBoundaryValues(int value) {
                // Test your method
            }
            """;
    }

    private String getStringTemplates() {
        return """
            String Boundary Tests:
            
            1. Empty/Blank checks:
               - null
               - empty string ""
               - blank string "   "
               - single character "a"
               - normal string "hello"
               
            2. Length boundaries:
               - Empty string (length 0)
               - Max allowed length
               - Max + 1 (if applicable)
               
            3. Special characters:
               - Unicode characters
               - Control characters
               - Newlines, tabs
               
            Example:
            @ParameterizedTest
            @NullAndEmptySource
            @ValueSource(strings = {"   ", "a", "hello", "特殊字符"})
            void testWithStringBoundaries(String value) {
                // Test your method
            }
            """;
    }

    private String getCollectionTemplates() {
        return """
            Collection Boundary Tests:
            
            1. Size boundaries:
               - null collection
               - empty collection (size 0)
               - single element (size 1)
               - multiple elements
               - max capacity (if applicable)
               
            2. Content checks:
               - Contains existing element
               - Contains non-existing element
               - Contains null element
               
            3. Modification:
               - Add to full collection
               - Remove from empty collection
               - Concurrent modification
               
            Example:
            @Test
            void testWithEmptyList() {
                List<String> emptyList = Collections.emptyList();
                // Test your method
            }
            
            @Test
            void testWithSingleElement() {
                List<String> singletonList = List.of("one");
                // Test your method
            }
            """;
    }

    private String getNullTemplates() {
        return """
            Null Handling Tests:
            
            1. Direct null checks:
               - Pass null as parameter
               - Verify NullPointerException or null return
               
            2. Optional handling:
               - Test with Optional.empty()
               - Test with Optional.of(value)
               - Test with Optional.ofNullable(null)
               
            3. Chained null checks:
               - object.getChild().getValue() chain
               - Test each level being null
               
            Example:
            @Test
            void testWithNullInput() {
                assertThrows(NullPointerException.class, () -> 
                    service.process(null));
            }
            
            @Test
            void testHandlesNullGracefully() {
                String result = service.processOrDefault(null);
                assertEquals("default", result);
            }
            """;
    }

    private String getBooleanTemplates() {
        return """
            Boolean Boundary Tests:
            
            1. Simple boolean:
               - Test with true
               - Test with false
               
            2. Compound conditions:
               - All true (A && B && C = true)
               - All false (A && B && C = false)
               - Mixed (A=true, B=false, C=true)
               - Short-circuit evaluation
               
            3. Boolean conversions:
               - String "true"/"false" parsing
               - Integer 0/1 conversion
               
            Example:
            @ParameterizedTest
            @CsvSource({
                "true, true, expected_result_1",
                "true, false, expected_result_2",
                "false, true, expected_result_3",
                "false, false, expected_result_4"
            })
            void testBooleanCombinations(boolean condA, boolean condB, String expected) {
                assertEquals(expected, service.process(condA, condB));
            }
            """;
    }

    private String getAllTemplates() {
        return getNumericTemplates() + "\n---\n\n" +
               getStringTemplates() + "\n---\n\n" +
               getCollectionTemplates() + "\n---\n\n" +
               getNullTemplates() + "\n---\n\n" +
               getBooleanTemplates();
    }

    // ==================== 数据类 ====================

    public enum ConditionType {
        NULL_CHECK,
        EMPTY_CHECK,
        SIZE_CHECK,
        NUMERIC_COMPARISON,
        EQUALITY,
        CONTAINS_CHECK,
        SWITCH,
        COMPOUND,
        BOOLEAN,
        UNKNOWN
    }

    @Data
    @Builder
    public static class BoundaryCondition {
        private String methodName;
        private int lineNumber;
        private ConditionType conditionType;
        private String expression;
        private String boundaryHint;
        private int branchCount;
        private boolean hasDefaultBranch;
    }

    @Data
    @Builder
    public static class BoundaryAnalysisResult {
        private String filePath;
        private String methodName;
        private int totalConditions;
        private List<BoundaryCondition> conditions;
        private List<String> testSuggestions;

        public String toAgentMessage() {
            StringBuilder sb = new StringBuilder();
            sb.append("Boundary Analysis Result:\n");
            sb.append(String.format("  File: %s\n", filePath));
            if (methodName != null) {
                sb.append(String.format("  Method: %s\n", methodName));
            }
            sb.append(String.format("  Total Conditions Found: %d\n\n", totalConditions));

            if (!conditions.isEmpty()) {
                sb.append("Conditions by Type:\n");
                conditions.stream()
                        .collect(Collectors.groupingBy(BoundaryCondition::getConditionType))
                        .forEach((type, list) -> {
                            sb.append(String.format("  %s: %d\n", type, list.size()));
                        });
            }

            if (!testSuggestions.isEmpty()) {
                sb.append("\nTest Suggestions:\n");
                for (int i = 0; i < Math.min(testSuggestions.size(), 15); i++) {
                    sb.append(String.format("  %d. %s\n", i + 1, testSuggestions.get(i)));
                }
                if (testSuggestions.size() > 15) {
                    sb.append(String.format("  ... and %d more suggestions\n", testSuggestions.size() - 15));
                }
            }

            return sb.toString();
        }
    }
}
