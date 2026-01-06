package com.codelogickeep.agent.ut.infra;

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

import dev.langchain4j.agent.tool.Tool;

public class CoverageToolImpl implements AgentTool {

    @Tool("Get the test coverage report summary (JaCoCo). Requires tests to be executed first.")
    public String getCoverageReport(String modulePath) throws IOException {
        // Assume standard Maven structure: target/site/jacoco/jacoco.xml
        Path reportPath = Paths.get(modulePath, "target", "site", "jacoco", "jacoco.xml");
        File xmlFile = reportPath.toFile();

        if (!xmlFile.exists()) {
            return "Coverage report not found at " + reportPath.toAbsolutePath() +
                    ". Make sure tests have been executed successfully.";
        }

        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // Security
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(xmlFile);
            doc.getDocumentElement().normalize();

            // Extract global counters
            StringBuilder sb = new StringBuilder();
            sb.append("Coverage Summary:\n");

            NodeList counters = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < counters.getLength(); i++) {
                Node node = counters.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE && "counter".equals(node.getNodeName())) {
                    Element element = (Element) node;
                    String type = element.getAttribute("type");
                    long missed = Long.parseLong(element.getAttribute("missed"));
                    long covered = Long.parseLong(element.getAttribute("covered"));
                    long total = missed + covered;
                    double percentage = total == 0 ? 0 : (double) covered / total * 100;

                    sb.append(String.format("  - %s: %.1f%% (%d/%d)\n", type, percentage, covered, total));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("Failed to parse coverage report: " + e.getMessage(), e);
        }
    }
}
