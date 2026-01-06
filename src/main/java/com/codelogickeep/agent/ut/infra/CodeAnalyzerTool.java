package com.codelogickeep.agent.ut.infra;

import com.codelogickeep.agent.ut.governance.GovernanceResource;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;

public interface CodeAnalyzerTool extends AgentTool {
    /**
     * Analyzes a Java source file and returns its structure (methods, dependencies).
     * @param path Path to the Java file.
     * @return Analysis result as a structured string (YAML/JSON like).
     */
    @Tool("Analyze a Java class structure to understand methods and fields")
    @GovernanceResource("file-read")
    String analyzeClass(String path) throws IOException;
}
