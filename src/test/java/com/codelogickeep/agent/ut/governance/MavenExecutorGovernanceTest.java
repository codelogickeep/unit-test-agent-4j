package com.codelogickeep.agent.ut.governance;

import com.codelogickeep.agent.ut.config.GovernanceConfig;
import com.codelogickeep.agent.ut.config.PolicyRule;
import com.codelogickeep.agent.ut.infra.MavenExecutorTool;
import com.codelogickeep.agent.ut.infra.MavenExecutorToolImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class MavenExecutorGovernanceTest {

    private static final String SAFE_REGEX = "[a-zA-Z0-9_$.#*-]+";

    @Test
    void testProxyAllowsMvnTest() {
        GovernanceConfig govConfig = new GovernanceConfig();
        govConfig.setEnabled(true);

        PolicyRule rule = new PolicyRule();
        rule.setResource("shell-exec");
        rule.setAction("ALLOW");
        rule.setCondition("testClassName.matches('" + SAFE_REGEX + "')");
        
        govConfig.setPolicy(Collections.singletonList(rule));

        MavenExecutorTool rawTool = new MavenExecutorToolImpl() {
            @Override
            public ExecutionResult executeTest(String testClassName) {
                return new ExecutionResult(0, "OK", "");
            }
        };

        MavenExecutorTool proxiedTool = ToolGovernanceProxy.createProxy(rawTool, MavenExecutorTool.class, govConfig);
        
        // Standard class
        assertDoesNotThrow(() -> proxiedTool.executeTest("com.example.MyTest"));
        // Inner class ($)
        assertDoesNotThrow(() -> proxiedTool.executeTest("com.example.Outer$InnerTest"));
        // Specific method (#)
        assertDoesNotThrow(() -> proxiedTool.executeTest("com.example.MyTest#testMethod"));
        // Wildcard (*)
        assertDoesNotThrow(() -> proxiedTool.executeTest("com.example.*Test"));
    }
    
    @Test
    void testProxyBlocksIllegalChars() {
        GovernanceConfig govConfig = new GovernanceConfig();
        govConfig.setEnabled(true);

        PolicyRule rule = new PolicyRule();
        rule.setResource("shell-exec");
        rule.setAction("ALLOW");
        rule.setCondition("testClassName.matches('" + SAFE_REGEX + "')");
        
        govConfig.setPolicy(Collections.singletonList(rule));

        MavenExecutorTool rawTool = new MavenExecutorToolImpl();
        MavenExecutorTool proxiedTool = ToolGovernanceProxy.createProxy(rawTool, MavenExecutorTool.class, govConfig);
        
        // Command injection attempts
        assertThrows(SecurityException.class, () -> proxiedTool.executeTest("com.example.MyTest; rm -rf /"));
        assertThrows(SecurityException.class, () -> proxiedTool.executeTest("com.example.MyTest && echo hacked"));
        assertThrows(SecurityException.class, () -> proxiedTool.executeTest("com.example.MyTest | whoami"));
    }
}
