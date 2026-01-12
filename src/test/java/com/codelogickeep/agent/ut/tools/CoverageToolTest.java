package com.codelogickeep.agent.ut.tools;

import com.codelogickeep.agent.ut.model.UncoveredMethod;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CoverageTool.
 * Tests JaCoCo XML report parsing and coverage analysis.
 */
class CoverageToolTest {

    private CoverageTool tool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        tool = new CoverageTool();
    }

    private void createJacocoReport(String content) throws IOException {
        Path jacocoDir = tempDir.resolve("target/site/jacoco");
        Files.createDirectories(jacocoDir);
        Files.writeString(jacocoDir.resolve("jacoco.xml"), content);
    }

    // ==================== getCoverageReport Tests ====================

    @Test
    @DisplayName("getCoverageReport should return summary for valid report")
    void getCoverageReport_shouldReturnSummaryForValidReport() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <counter type="INSTRUCTION" missed="10" covered="90"/>
                <counter type="LINE" missed="5" covered="45"/>
                <counter type="BRANCH" missed="2" covered="8"/>
                <counter type="METHOD" missed="1" covered="9"/>
                <counter type="CLASS" missed="0" covered="1"/>
            </report>
            """;
        createJacocoReport(jacocoXml);

        String result = tool.getCoverageReport(tempDir.toString());

        assertTrue(result.contains("Coverage Summary"));
        assertTrue(result.contains("LINE"));
        assertTrue(result.contains("BRANCH"));
        assertTrue(result.contains("METHOD"));
    }

    @Test
    @DisplayName("getCoverageReport should return error when report not found")
    void getCoverageReport_shouldReturnErrorWhenReportNotFound() throws IOException {
        String result = tool.getCoverageReport(tempDir.toString());

        assertTrue(result.contains("not found") || result.contains("Coverage report not found"));
    }

    // ==================== checkCoverageThreshold Tests ====================

    @Test
    @DisplayName("checkCoverageThreshold should return PASSED when coverage meets threshold")
    void checkCoverageThreshold_shouldReturnPassedWhenMeetsThreshold() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/MyService" sourcefilename="MyService.java">
                        <method name="process" desc="()V" line="10">
                            <counter type="LINE" missed="0" covered="10"/>
                            <counter type="BRANCH" missed="0" covered="4"/>
                        </method>
                        <counter type="LINE" missed="5" covered="95"/>
                        <counter type="BRANCH" missed="1" covered="9"/>
                        <counter type="METHOD" missed="0" covered="5"/>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        String result = tool.checkCoverageThreshold(tempDir.toString(), "com.example.MyService", 80);

        assertTrue(result.contains("PASSED") || result.contains("meets threshold"));
    }

    @Test
    @DisplayName("checkCoverageThreshold should return FAILED when coverage below threshold")
    void checkCoverageThreshold_shouldReturnFailedWhenBelowThreshold() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/MyService" sourcefilename="MyService.java">
                        <method name="process" desc="()V" line="10">
                            <counter type="LINE" missed="8" covered="2"/>
                        </method>
                        <counter type="LINE" missed="70" covered="30"/>
                        <counter type="BRANCH" missed="5" covered="5"/>
                        <counter type="METHOD" missed="2" covered="3"/>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        String result = tool.checkCoverageThreshold(tempDir.toString(), "com.example.MyService", 80);

        assertTrue(result.contains("FAILED") || result.contains("below threshold"));
    }

    @Test
    @DisplayName("checkCoverageThreshold should list uncovered methods")
    void checkCoverageThreshold_shouldListUncoveredMethods() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/MyService" sourcefilename="MyService.java">
                        <method name="coveredMethod" desc="()V" line="10">
                            <counter type="LINE" missed="0" covered="10"/>
                        </method>
                        <method name="uncoveredMethod" desc="(I)V" line="20">
                            <counter type="LINE" missed="10" covered="0"/>
                        </method>
                        <counter type="LINE" missed="50" covered="50"/>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        String result = tool.checkCoverageThreshold(tempDir.toString(), "com.example.MyService", 80);

        assertTrue(result.contains("uncoveredMethod"));
    }

    @Test
    @DisplayName("checkCoverageThreshold should return error for non-existing class")
    void checkCoverageThreshold_shouldReturnErrorForNonExistingClass() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/ExistingClass" sourcefilename="ExistingClass.java">
                        <counter type="LINE" missed="0" covered="10"/>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        String result = tool.checkCoverageThreshold(tempDir.toString(), "com.example.NonExisting", 80);

        assertTrue(result.contains("ERROR"));
    }

    @Test
    @DisplayName("checkCoverageThreshold should return error when report not found")
    void checkCoverageThreshold_shouldReturnErrorWhenReportNotFound() throws IOException {
        String result = tool.checkCoverageThreshold(tempDir.toString(), "com.example.MyService", 80);

        assertTrue(result.contains("ERROR"));
    }

    // ==================== getMethodCoverageDetails Tests ====================

    @Test
    @DisplayName("getMethodCoverageDetails should return detailed method coverage")
    void getMethodCoverageDetails_shouldReturnDetailedMethodCoverage() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/MyService" sourcefilename="MyService.java">
                        <method name="methodA" desc="()V" line="10">
                            <counter type="LINE" missed="0" covered="10"/>
                            <counter type="BRANCH" missed="0" covered="2"/>
                        </method>
                        <method name="methodB" desc="(II)I" line="20">
                            <counter type="LINE" missed="5" covered="5"/>
                            <counter type="BRANCH" missed="1" covered="1"/>
                        </method>
                        <method name="methodC" desc="()V" line="30">
                            <counter type="LINE" missed="10" covered="0"/>
                        </method>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        String result = tool.getMethodCoverageDetails(tempDir.toString(), "com.example.MyService");

        assertTrue(result.contains("methodA"));
        assertTrue(result.contains("methodB"));
        assertTrue(result.contains("methodC"));
        assertTrue(result.contains("Line:"));
        assertTrue(result.contains("Branch:"));
    }

    @Test
    @DisplayName("getMethodCoverageDetails should show coverage symbols")
    void getMethodCoverageDetails_shouldShowCoverageSymbols() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/MyService" sourcefilename="MyService.java">
                        <method name="goodMethod" desc="()V" line="10">
                            <counter type="LINE" missed="0" covered="100"/>
                        </method>
                        <method name="partialMethod" desc="()V" line="20">
                            <counter type="LINE" missed="40" covered="60"/>
                        </method>
                        <method name="badMethod" desc="()V" line="30">
                            <counter type="LINE" missed="100" covered="0"/>
                        </method>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        String result = tool.getMethodCoverageDetails(tempDir.toString(), "com.example.MyService");

        // Should contain coverage indicators
        assertTrue(result.contains("✓") || result.contains("Good"));
        assertTrue(result.contains("◐") || result.contains("Partial"));
        assertTrue(result.contains("✗") || result.contains("No coverage"));
    }

    // ==================== getUncoveredMethodsCompact Tests ====================

    @Test
    @DisplayName("getUncoveredMethodsCompact should return PASS when all methods covered")
    void getUncoveredMethodsCompact_shouldReturnPassWhenAllCovered() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/MyService" sourcefilename="MyService.java">
                        <method name="method1" desc="()V" line="10">
                            <counter type="LINE" missed="0" covered="100"/>
                        </method>
                        <counter type="LINE" missed="0" covered="100"/>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        String result = tool.getUncoveredMethodsCompact(tempDir.toString(), "com.example.MyService", 80);

        assertTrue(result.contains("PASS"));
    }

    @Test
    @DisplayName("getUncoveredMethodsCompact should list uncovered methods compactly")
    void getUncoveredMethodsCompact_shouldListUncoveredMethodsCompactly() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/MyService" sourcefilename="MyService.java">
                        <method name="uncovered1" desc="()V" line="10">
                            <counter type="LINE" missed="10" covered="0"/>
                        </method>
                        <method name="uncovered2" desc="(I)I" line="20">
                            <counter type="LINE" missed="8" covered="2"/>
                        </method>
                        <counter type="LINE" missed="50" covered="50"/>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        String result = tool.getUncoveredMethodsCompact(tempDir.toString(), "com.example.MyService", 80);

        assertTrue(result.contains("uncovered1"));
        assertTrue(result.contains("uncovered2"));
        assertTrue(result.contains("Uncovered"));
    }

    // ==================== getUncoveredMethodsList Tests ====================

    @Test
    @DisplayName("getUncoveredMethodsList should return structured list")
    void getUncoveredMethodsList_shouldReturnStructuredList() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/MyService" sourcefilename="MyService.java">
                        <method name="uncoveredMethod" desc="(II)V" line="10">
                            <counter type="LINE" missed="10" covered="0"/>
                            <counter type="BRANCH" missed="2" covered="0"/>
                        </method>
                        <method name="coveredMethod" desc="()V" line="20">
                            <counter type="LINE" missed="0" covered="10"/>
                        </method>
                        <counter type="LINE" missed="10" covered="10"/>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        List<UncoveredMethod> result = tool.getUncoveredMethodsList(tempDir.toString(), "com.example.MyService", 80);

        assertEquals(1, result.size());
        assertEquals("uncoveredMethod", result.get(0).getMethodName());
        assertEquals(0.0, result.get(0).getLineCoverage());
    }

    @Test
    @DisplayName("getUncoveredMethodsList should return empty list when all covered")
    void getUncoveredMethodsList_shouldReturnEmptyListWhenAllCovered() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/MyService" sourcefilename="MyService.java">
                        <method name="coveredMethod" desc="()V" line="10">
                            <counter type="LINE" missed="0" covered="100"/>
                        </method>
                        <counter type="LINE" missed="0" covered="100"/>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        List<UncoveredMethod> result = tool.getUncoveredMethodsList(tempDir.toString(), "com.example.MyService", 80);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getUncoveredMethodsList should return empty list when report not found")
    void getUncoveredMethodsList_shouldReturnEmptyListWhenReportNotFound() throws IOException {
        List<UncoveredMethod> result = tool.getUncoveredMethodsList(tempDir.toString(), "com.example.MyService", 80);

        assertTrue(result.isEmpty());
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("should handle constructor coverage")
    void shouldHandleConstructorCoverage() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/MyService" sourcefilename="MyService.java">
                        <method name="&lt;init&gt;" desc="()V" line="5">
                            <counter type="LINE" missed="5" covered="0"/>
                        </method>
                        <counter type="LINE" missed="5" covered="0"/>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        String result = tool.getMethodCoverageDetails(tempDir.toString(), "com.example.MyService");

        assertTrue(result.contains("constructor"));
    }

    @Test
    @DisplayName("should skip static initializer")
    void shouldSkipStaticInitializer() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/MyService" sourcefilename="MyService.java">
                        <method name="&lt;clinit&gt;" desc="()V" line="3">
                            <counter type="LINE" missed="5" covered="0"/>
                        </method>
                        <method name="normalMethod" desc="()V" line="10">
                            <counter type="LINE" missed="0" covered="10"/>
                        </method>
                        <counter type="LINE" missed="5" covered="10"/>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        String result = tool.getMethodCoverageDetails(tempDir.toString(), "com.example.MyService");

        assertFalse(result.contains("clinit"));
        assertTrue(result.contains("normalMethod"));
    }

    @Test
    @DisplayName("should handle class with no methods")
    void shouldHandleClassWithNoMethods() throws IOException {
        String jacocoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test-report">
                <package name="com/example">
                    <class name="com/example/EmptyClass" sourcefilename="EmptyClass.java">
                        <counter type="LINE" missed="0" covered="0"/>
                    </class>
                </package>
            </report>
            """;
        createJacocoReport(jacocoXml);

        String result = tool.getMethodCoverageDetails(tempDir.toString(), "com.example.EmptyClass");

        assertTrue(result.contains("EmptyClass"));
    }
}
