package com.codelogickeep.agent.ut.config;

import lombok.Data;

@Data
public class PolicyRule {
    private String resource;
    private String action;
    private String condition;
}

