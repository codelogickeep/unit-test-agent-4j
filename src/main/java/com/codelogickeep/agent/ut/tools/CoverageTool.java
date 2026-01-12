package com.codelogickeep.agent.ut.tools;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.codelogickeep.agent.ut.model.UncoveredMethod;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoverageTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(CoverageTool.class);

    @Tool("Get the test coverage report summary (JaCoCo). Requires tests to be executed first.")
    public String getCoverageReport(@P("Path to the module directory where target/site/jacoco/jacoco.xml is located") String modulePath) throws IOException {
        log.info("Tool Input - getCoverageReport: modulePath={}", modulePath);
        Path reportPath = Paths.get(modulePath, "target", "site", "jacoco", "jacoco.xml");
        File xmlFile = reportPath.toFile();

        if (!xmlFile.exists()) {
            return "Coverage report not found at " + reportPath.toAbsolutePath() +
                    ". Make sure tests have been executed with JaCoCo enabled (mvn test jacoco:report).";
        }

        try {
            Document doc = parseXml(xmlFile);
            StringBuilder sb = new StringBuilder();
            sb.append("Coverage Summary:\n");
            appendCounters(doc.getDocumentElement(), sb, "  ");
            String result = sb.toString();
            log.info("Tool Output - getCoverageReport: length={}", result.length());
            return result;
        } catch (Exception e) {
            log.error("Failed to parse coverage report", e);
            throw new IOException("Failed to parse coverage report: " + e.getMessage(), e);
        }
    }

    @Tool("Check if coverage meets the threshold and return uncovered methods for a specific class. Use this to determine if more tests are needed.")
    public String checkCoverageThreshold(
            @P("Path to the module directory") String modulePath,
            @P("Fully qualified class name to check (e.g., com.example.MyService)") String className,
            @P("Coverage threshold percentage (0-100)") int threshold) throws IOException {

        log.info("Tool Input - checkCoverageThreshold: modulePath={}, className={}, threshold={}", modulePath, className, threshold);
        Path reportPath = Paths.get(modulePath, "target", "site", "jacoco", "jacoco.xml");
        File xmlFile = reportPath.toFile();

        if (!xmlFile.exists()) {
            return "ERROR: Coverage report not found. Run 'mvn test jacoco:report' first.";
        }

        try {
            Document doc = parseXml(xmlFile);
            String packageName = className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
            String simpleClassName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;

            // Find the package
            NodeList packages = doc.getElementsByTagName("package");
            Element targetPackage = null;
            for (int i = 0; i < packages.getLength(); i++) {
                Element pkg = (Element) packages.item(i);
                if (pkg.getAttribute("name").replace('/', '.').equals(packageName)) {
                    targetPackage = pkg;
                    break;
                }
            }

            if (targetPackage == null) {
                return "ERROR: Package '" + packageName + "' not found in coverage report.";
            }

            // Find the class
            NodeList classes = targetPackage.getElementsByTagName("class");
            Element targetClass = null;
            for (int i = 0; i < classes.getLength(); i++) {
                Element cls = (Element) classes.item(i);
                String clsName = cls.getAttribute("name");
                if (clsName.endsWith("/" + simpleClassName) || clsName.equals(simpleClassName)) {
                    targetClass = cls;
                    break;
                }
            }

            if (targetClass == null) {
                return "ERROR: Class '" + simpleClassName + "' not found in package '" + packageName + "'.";
            }

            // Calculate class-level coverage
            double lineCoverage = calculateCoverage(targetClass, "LINE");
            double branchCoverage = calculateCoverage(targetClass, "BRANCH");
            double methodCoverage = calculateCoverage(targetClass, "METHOD");

            StringBuilder result = new StringBuilder();
            result.append("Coverage Analysis for: ").append(className).append("\n");
            result.append("─".repeat(50)).append("\n");
            result.append(String.format("Line Coverage:   %.1f%% (threshold: %d%%)\n", lineCoverage, threshold));
            result.append(String.format("Branch Coverage: %.1f%%\n", branchCoverage));
            result.append(String.format("Method Coverage: %.1f%%\n", methodCoverage));
            result.append("─".repeat(50)).append("\n");

            boolean meetsThreshold = lineCoverage >= threshold;
            if (meetsThreshold) {
                result.append("✓ PASSED: Coverage meets threshold.\n");
            } else {
                result.append("✗ FAILED: Coverage below threshold.\n\n");
                result.append("Uncovered/Partially Covered Methods:\n");

                List<String> uncoveredMethods = findUncoveredMethods(targetClass);
                if (uncoveredMethods.isEmpty()) {
                    result.append("  (No method-level details available)\n");
                } else {
                    for (String method : uncoveredMethods) {
                        result.append("  - ").append(method).append("\n");
                    }
                }
                result.append("\nRecommendation: Add tests for the uncovered methods listed above.");
            }

            String finalResult = result.toString();
            log.info("Tool Output - checkCoverageThreshold: meetsThreshold={}", meetsThreshold);
            return finalResult;
        } catch (Exception e) {
            log.error("Failed to analyze coverage", e);
            throw new IOException("Failed to analyze coverage: " + e.getMessage(), e);
        }
    }

    @Tool("Get detailed coverage for all methods in a class, showing which methods need more tests.")
    public String getMethodCoverageDetails(
            @P("Path to the module directory") String modulePath,
            @P("Fully qualified class name (e.g., com.example.MyService)") String className) throws IOException {

        log.info("Tool Input - getMethodCoverageDetails: modulePath={}, className={}", modulePath, className);
        Path reportPath = Paths.get(modulePath, "target", "site", "jacoco", "jacoco.xml");
        File xmlFile = reportPath.toFile();

        if (!xmlFile.exists()) {
            return "ERROR: Coverage report not found. Run 'mvn test jacoco:report' first.";
        }

        try {
            Document doc = parseXml(xmlFile);
            String packageName = className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
            String simpleClassName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;

            // Find package and class
            NodeList packages = doc.getElementsByTagName("package");
            for (int i = 0; i < packages.getLength(); i++) {
                Element pkg = (Element) packages.item(i);
                if (!pkg.getAttribute("name").replace('/', '.').equals(packageName)) continue;

                NodeList classes = pkg.getElementsByTagName("class");
                for (int j = 0; j < classes.getLength(); j++) {
                    Element cls = (Element) classes.item(j);
                    String clsName = cls.getAttribute("name");
                    if (!clsName.endsWith("/" + simpleClassName) && !clsName.equals(simpleClassName)) continue;

                    StringBuilder result = new StringBuilder();
                    result.append("Method Coverage Details: ").append(className).append("\n");
                    result.append("═".repeat(60)).append("\n");

                    NodeList methods = cls.getElementsByTagName("method");
                    for (int k = 0; k < methods.getLength(); k++) {
                        Element method = (Element) methods.item(k);
                        String methodName = method.getAttribute("name");
                        String desc = method.getAttribute("desc");

                        // Skip constructors and static initializers for cleaner output
                        if ("<clinit>".equals(methodName)) continue;

                        double lineCov = calculateCoverage(method, "LINE");
                        double branchCov = calculateCoverage(method, "BRANCH");

                        String status = lineCov >= 80 ? "✓" : (lineCov > 0 ? "◐" : "✗");
                        String displayName = "<init>".equals(methodName) ? "constructor" : methodName;

                        result.append(String.format("%s %-30s Line: %5.1f%%  Branch: %5.1f%%\n",
                                status, displayName + parseDescriptor(desc), lineCov, branchCov));
                    }

                    result.append("═".repeat(60)).append("\n");
                    result.append("Legend: ✓ = Good (≥80%)  ◐ = Partial  ✗ = No coverage\n");
                    String finalResult = result.toString();
                    log.info("Tool Output - getMethodCoverageDetails: length={}", finalResult.length());
                    return finalResult;
                }
            }
            String errorMsg = "ERROR: Class '" + className + "' not found in coverage report.";
            log.info("Tool Output - getMethodCoverageDetails: {}", errorMsg);
            return errorMsg;
        } catch (Exception e) {
            log.error("Failed to get method details", e);
            throw new IOException("Failed to get method details: " + e.getMessage(), e);
        }
    }

    private Document parseXml(File xmlFile) throws Exception {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        // Allow DOCTYPE for JaCoCo XML reports
        dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(xmlFile);
        doc.getDocumentElement().normalize();
        return doc;
    }

    private void appendCounters(Element element, StringBuilder sb, String indent) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "counter".equals(node.getNodeName())) {
                Element counter = (Element) node;
                String type = counter.getAttribute("type");
                long missed = Long.parseLong(counter.getAttribute("missed"));
                long covered = Long.parseLong(counter.getAttribute("covered"));
                long total = missed + covered;
                double percentage = total == 0 ? 0 : (double) covered / total * 100;
                sb.append(String.format("%s- %s: %.1f%% (%d/%d)\n", indent, type, percentage, covered, total));
            }
        }
    }

    private double calculateCoverage(Element element, String counterType) {
        NodeList counters = element.getElementsByTagName("counter");
        for (int i = 0; i < counters.getLength(); i++) {
            Element counter = (Element) counters.item(i);
            if (counter.getParentNode() != element) continue; // Only direct children
            if (counterType.equals(counter.getAttribute("type"))) {
                long missed = Long.parseLong(counter.getAttribute("missed"));
                long covered = Long.parseLong(counter.getAttribute("covered"));
                long total = missed + covered;
                return total == 0 ? 100.0 : (double) covered / total * 100;
            }
        }
        return 100.0; // No counter means fully covered or not applicable
    }

    private List<String> findUncoveredMethods(Element classElement) {
        List<String> uncovered = new ArrayList<>();
        NodeList methods = classElement.getElementsByTagName("method");

        for (int i = 0; i < methods.getLength(); i++) {
            Element method = (Element) methods.item(i);
            if (method.getParentNode() != classElement) continue;

            String methodName = method.getAttribute("name");
            if ("<clinit>".equals(methodName)) continue;

            double lineCoverage = calculateCoverage(method, "LINE");
            if (lineCoverage < 80) {
                String displayName = "<init>".equals(methodName) ? "constructor" : methodName;
                String desc = method.getAttribute("desc");
                uncovered.add(String.format("%s%s (%.0f%% covered)", displayName, parseDescriptor(desc), lineCoverage));
            }
        }
        return uncovered;
    }

    private String parseDescriptor(String desc) {
        // Simplified descriptor parsing for display
        if (desc == null || desc.isEmpty()) return "()";

        int paramEnd = desc.indexOf(')');
        if (paramEnd <= 1) return "()";

        String params = desc.substring(1, paramEnd);
        if (params.isEmpty()) return "()";

        // Count parameters (simplified)
        int count = 0;
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            if (c == 'L') {
                i = params.indexOf(';', i) + 1;
            } else if (c == '[') {
                i++;
                continue;
            } else {
                i++;
            }
            count++;
        }
        return count == 0 ? "()" : "(" + count + " params)";
    }

    @Tool("Get compact uncovered methods list for LLM test generation (minimal token usage)")
    public String getUncoveredMethodsCompact(
            @P("Path to the module directory") String modulePath,
            @P("Fully qualified class name") String className,
            @P("Coverage threshold percentage") int threshold) throws IOException {

        log.info("Tool Input - getUncoveredMethodsCompact: modulePath={}, className={}, threshold={}", modulePath, className, threshold);
        Path reportPath = Paths.get(modulePath, "target", "site", "jacoco", "jacoco.xml");
        File xmlFile = reportPath.toFile();

        if (!xmlFile.exists()) {
            return "ERROR: No coverage report. Run tests first.";
        }

        try {
            Document doc = parseXml(xmlFile);
            String packageName = className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
            String simpleClassName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;

            Element targetClass = findClass(doc, packageName, simpleClassName);
            if (targetClass == null) {
                return "ERROR: Class not found: " + className;
            }

            double lineCoverage = calculateCoverage(targetClass, "LINE");
            if (lineCoverage >= threshold) {
                return "PASS: " + className + " coverage " + String.format("%.0f%%", lineCoverage) + " >= " + threshold + "%";
            }

            StringBuilder result = new StringBuilder();
            result.append("Class: ").append(className).append("\n");
            result.append("Coverage: ").append(String.format("%.0f%%", lineCoverage)).append(" (need ").append(threshold).append("%)\n");
            result.append("Uncovered:\n");

            NodeList methods = targetClass.getElementsByTagName("method");
            int uncoveredCount = 0;
            for (int i = 0; i < methods.getLength(); i++) {
                Element method = (Element) methods.item(i);
                if (method.getParentNode() != targetClass) continue;

                String methodName = method.getAttribute("name");
                if ("<clinit>".equals(methodName)) continue;

                double methodCov = calculateCoverage(method, "LINE");
                if (methodCov < threshold) {
                    String displayName = "<init>".equals(methodName) ? "constructor" : methodName;
                    String desc = method.getAttribute("desc");
                    result.append("- ").append(displayName).append(parseDescriptor(desc))
                          .append(" : ").append(String.format("%.0f%%", methodCov)).append("\n");
                    uncoveredCount++;
                }
            }

            if (uncoveredCount == 0) {
                String passMsg = "PASS: All methods meet threshold";
                log.info("Tool Output - getUncoveredMethodsCompact: {}", passMsg);
                return passMsg;
            }

            String finalResult = result.toString();
            log.info("Tool Output - getUncoveredMethodsCompact: uncoveredCount={}", uncoveredCount);
            return finalResult;
        } catch (Exception e) {
            log.error("Failed to get compact uncovered methods", e);
            throw new IOException("Failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get uncovered methods as structured list (for BatchAnalyzer)
     */
    public List<UncoveredMethod> getUncoveredMethodsList(String modulePath, String className, int threshold) throws IOException {
        List<UncoveredMethod> result = new ArrayList<>();
        Path reportPath = Paths.get(modulePath, "target", "site", "jacoco", "jacoco.xml");
        File xmlFile = reportPath.toFile();

        if (!xmlFile.exists()) {
            return result;
        }

        try {
            Document doc = parseXml(xmlFile);
            String packageName = className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
            String simpleClassName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;

            Element targetClass = findClass(doc, packageName, simpleClassName);
            if (targetClass == null) {
                return result;
            }

            NodeList methods = targetClass.getElementsByTagName("method");
            for (int i = 0; i < methods.getLength(); i++) {
                Element method = (Element) methods.item(i);
                if (method.getParentNode() != targetClass) continue;

                String methodName = method.getAttribute("name");
                if ("<clinit>".equals(methodName)) continue;

                double lineCov = calculateCoverage(method, "LINE");
                double branchCov = calculateCoverage(method, "BRANCH");

                if (lineCov < threshold) {
                    String displayName = "<init>".equals(methodName) ? "constructor" : methodName;
                    String desc = method.getAttribute("desc");

                    result.add(UncoveredMethod.builder()
                            .methodName(displayName)
                            .signature(displayName + parseDescriptor(desc))
                            .lineCoverage(lineCov)
                            .branchCoverage(branchCov)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to get uncovered methods list", e);
        }

        return result;
    }

    private Element findClass(Document doc, String packageName, String simpleClassName) {
        NodeList packages = doc.getElementsByTagName("package");
        for (int i = 0; i < packages.getLength(); i++) {
            Element pkg = (Element) packages.item(i);
            if (!pkg.getAttribute("name").replace('/', '.').equals(packageName)) continue;

            NodeList classes = pkg.getElementsByTagName("class");
            for (int j = 0; j < classes.getLength(); j++) {
                Element cls = (Element) classes.item(j);
                String clsName = cls.getAttribute("name");
                if (clsName.endsWith("/" + simpleClassName) || clsName.equals(simpleClassName)) {
                    return cls;
                }
            }
        }
        return null;
    }
}
