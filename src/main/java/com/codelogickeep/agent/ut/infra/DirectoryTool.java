package com.codelogickeep.agent.ut.infra;

import com.codelogickeep.agent.ut.governance.GovernanceResource;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.util.List;

public interface DirectoryTool extends AgentTool {
    @Tool("List files and directories in a given path. Returns names suffixed with '/' if they are directories.")
    @GovernanceResource("file-read")
    List<String> listFiles(String path) throws IOException;

    @Tool("Create a directory and all non-existent parent directories.")
    @GovernanceResource("file-write")
    void createDirectory(String path) throws IOException;
}
