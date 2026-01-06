package com.codelogickeep.agent.ut.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Context {
    private String targetFile;
    private String projectRoot;
    
    // Governance context management is now handled internally or via different mechanisms if needed.
    // Since we removed governor-core, we remove the AgentContextHolder usage.
    
    public static void setupGovernanceContext(String userId) {
        // No-op for now as we don't have a replacement ThreadLocal context yet.
        // If needed, we can implement a simple ThreadLocal here.
    }
    
    public static void clearGovernanceContext() {
        // No-op
    }
}
