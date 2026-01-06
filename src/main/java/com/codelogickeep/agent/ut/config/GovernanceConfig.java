package com.codelogickeep.agent.ut.config;

import lombok.Data;
import java.util.List;

@Data
public class GovernanceConfig {
    private boolean enabled;
    private List<PolicyRule> policy;
}

