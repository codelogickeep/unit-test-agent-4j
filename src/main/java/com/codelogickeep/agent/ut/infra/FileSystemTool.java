package com.codelogickeep.agent.ut.infra;

import com.codelogickeep.agent.ut.governance.GovernanceResource;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;

public interface FileSystemTool extends AgentTool {
    @Tool("Read the content of a file")
    @GovernanceResource("file-read")
    String readFile(String path) throws IOException;

    @Tool("Write content to a file. Create directories if they don't exist. Overwrites existing content.")
    @GovernanceResource("file-write")
    void writeFile(String path, String content) throws IOException;

    @Tool("Replace content in a file starting from a specific line number (1-based) to the end. CAUTION: This truncates the file at startLine before writing.")
    @GovernanceResource("file-write")
    void writeFileFromLine(String path, String content, int startLine) throws IOException;
}
