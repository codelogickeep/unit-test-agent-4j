package com.codelogickeep.agent.ut.governance;

import com.codelogickeep.agent.ut.config.GovernanceConfig;
import com.codelogickeep.agent.ut.config.PolicyRule;
import com.codelogickeep.agent.ut.infra.FileSystemTool;
import com.codelogickeep.agent.ut.infra.FileSystemToolImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ToolGovernanceProxyTest {

    @Test
    void testProxyAllowsValidWrite() throws IOException {
        // Setup Config
        GovernanceConfig govConfig = new GovernanceConfig();
        govConfig.setEnabled(true);
        
        PolicyRule rule = new PolicyRule();
        rule.setResource("file-write");
        rule.setAction("ALLOW");
        rule.setCondition("path.contains('/src/test/java/')"); 
        
        govConfig.setPolicy(Collections.singletonList(rule));

        // Setup Tool
        FileSystemTool rawTool = new FileSystemToolImpl() {
            @Override
            public void writeFile(String path, String content) {
                // Mock implementation to avoid actual file write
            }
        };
        
        // Updated to pass GovernanceConfig directly
        FileSystemTool proxiedTool = ToolGovernanceProxy.createProxy(rawTool, FileSystemTool.class, govConfig);

        // Test Allowed
        assertDoesNotThrow(() -> proxiedTool.writeFile("/project/src/test/java/Test.java", "content"));
    }

    @Test
    void testProxyBlocksInvalidWrite() {
        GovernanceConfig govConfig = new GovernanceConfig();
        govConfig.setEnabled(true);
        
        PolicyRule rule = new PolicyRule();
        rule.setResource("file-write");
        rule.setAction("ALLOW");
        rule.setCondition("path.contains('/src/test/java/')"); 
        
        govConfig.setPolicy(Collections.singletonList(rule));

        FileSystemTool rawTool = new FileSystemToolImpl();
        // Updated to pass GovernanceConfig directly
        FileSystemTool proxiedTool = ToolGovernanceProxy.createProxy(rawTool, FileSystemTool.class, govConfig);

        // Test Denied
        SecurityException exception = assertThrows(SecurityException.class, () -> 
            proxiedTool.writeFile("/project/src/main/java/Main.java", "content")
        );
        System.out.println("Blocked as expected: " + exception.getMessage());
    }
}
