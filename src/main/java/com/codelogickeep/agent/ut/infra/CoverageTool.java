package com.codelogickeep.agent.ut.infra;

import com.codelogickeep.agent.ut.governance.GovernanceResource;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;

public interface CoverageTool extends AgentTool {
    /**
     * Reads the JaCoCo XML report and returns a summary string.
     * @return Coverage summary or error message.
     */
    @Tool("Get the test coverage report summary (JaCoCo). Requires tests to be executed first.")
    @GovernanceResource("file-read")
    String getCoverageReport(String modulePath) throws IOException;
}

