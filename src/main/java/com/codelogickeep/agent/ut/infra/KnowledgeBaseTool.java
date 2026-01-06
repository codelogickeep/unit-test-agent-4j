package com.codelogickeep.agent.ut.infra;

import com.codelogickeep.agent.ut.governance.GovernanceResource;
import dev.langchain4j.agent.tool.Tool;

public interface KnowledgeBaseTool extends AgentTool {
    @Tool("Search for information in the knowledge base. Use this to find existing unit test examples, coding guidelines, or project-specific patterns to ensure generated tests match the project style.")
    @GovernanceResource("file-read")
    String searchKnowledge(String query);
}
