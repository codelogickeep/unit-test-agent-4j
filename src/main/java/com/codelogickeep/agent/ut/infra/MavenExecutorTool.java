package com.codelogickeep.agent.ut.infra;

import com.codelogickeep.agent.ut.governance.GovernanceResource;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;

public interface MavenExecutorTool extends AgentTool {
    @Tool("Execute Maven tests for a specific class. Returns exit code and output.")
    @GovernanceResource("shell-exec")
    ExecutionResult executeTest(String testClassName) throws IOException, InterruptedException;

    @Tool("Compile the project (src and test) using Maven. Useful to check for syntax errors before running tests.")
    @GovernanceResource("shell-exec")
    ExecutionResult compileProject() throws IOException, InterruptedException;

    record ExecutionResult(int exitCode, String stdOut, String stdErr) {}
}

