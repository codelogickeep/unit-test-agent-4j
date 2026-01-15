package com.codelogickeep.agent.ut.framework.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IterationStats 单元测试
 */
class IterationStatsTest {

    private IterationStats stats;

    @BeforeEach
    void setUp() {
        stats = new IterationStats("/test/path/TestClass.java");
    }

    @Test
    void testEmptyMethodList() {
        // 测试空方法列表生成报告不会报错
        String report = stats.generateMarkdownReport();
        
        assertNotNull(report);
        assertTrue(report.contains("单元测试生成报告"));
        assertTrue(report.contains("/test/path/TestClass.java"));
    }

    @Test
    void testMethodWithZeroTokens() {
        // 测试方法没有 token 记录时不会除零
        IterationStats.MethodStats method1 = stats.startMethod("method1", "P0");
        IterationStats.MethodStats method2 = stats.startMethod("method2", "P1");
        
        // 不添加任何 token（模拟没有记录到 token 的情况）
        method1.complete("SUCCESS", 80.0);
        method2.complete("SUCCESS", 90.0);
        
        // 应该不抛出异常
        String report = stats.generateMarkdownReport();
        
        assertNotNull(report);
        assertTrue(report.contains("No token data recorded") || report.contains("method1"));
    }

    @Test
    void testMethodWithTokens() {
        // 测试正常有 token 的情况
        IterationStats.MethodStats method1 = stats.startMethod("method1", "P0");
        method1.addPromptTokens(1000);
        method1.addResponseTokens(500);
        method1.complete("SUCCESS", 85.5);
        
        IterationStats.MethodStats method2 = stats.startMethod("method2", "P1");
        method2.addPromptTokens(800);
        method2.addResponseTokens(400);
        method2.complete("SUCCESS", 92.0);
        
        stats.recordPromptSize(1800);
        stats.recordResponseSize(900);
        
        String report = stats.generateMarkdownReport();
        
        assertNotNull(report);
        assertTrue(report.contains("method1"));
        assertTrue(report.contains("method2"));
        assertTrue(report.contains("85.5%") || report.contains("85,5%"));
    }

    @Test
    void testMixedTokenScenarios() {
        // 测试混合场景：部分方法有 token，部分没有
        IterationStats.MethodStats method1 = stats.startMethod("withTokens", "P0");
        method1.addPromptTokens(500);
        method1.addResponseTokens(200);
        method1.complete("SUCCESS", 75.0);
        
        IterationStats.MethodStats method2 = stats.startMethod("noTokens", "P1");
        // method2 没有添加 token
        method2.complete("SUCCESS", 80.0);
        
        IterationStats.MethodStats method3 = stats.startMethod("alsoWithTokens", "P2");
        method3.addPromptTokens(300);
        method3.addResponseTokens(100);
        method3.complete("FAILED", 0.0);
        
        String report = stats.generateMarkdownReport();
        
        assertNotNull(report);
        assertTrue(report.contains("withTokens"));
        assertTrue(report.contains("noTokens"));
        assertTrue(report.contains("alsoWithTokens"));
    }

    @Test
    void testSaveReport(@TempDir Path tempDir) throws IOException {
        // 测试保存报告到文件
        IterationStats.MethodStats method = stats.startMethod("testMethod", "P0");
        method.addPromptTokens(100);
        method.addResponseTokens(50);
        method.complete("SUCCESS", 90.0);
        
        stats.recordPromptSize(100);
        stats.recordResponseSize(50);
        
        // 保存报告
        stats.saveReport(tempDir);
        
        // 验证文件是否创建
        List<Path> files = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .toList();
        
        assertEquals(1, files.size());
        
        String content = Files.readString(files.get(0));
        assertTrue(content.contains("testMethod"));
        assertTrue(content.contains("90.0%") || content.contains("90,0%"));
    }

    @Test
    void testMethodStatsIsSuccess() {
        // 测试 isSuccess 方法的各种情况
        IterationStats.MethodStats method1 = stats.startMethod("m1", "P0");
        method1.complete("SUCCESS", 100);
        assertTrue(method1.isSuccess());
        
        IterationStats.MethodStats method2 = stats.startMethod("m2", "P0");
        method2.complete("COMPLETED", 100);
        assertTrue(method2.isSuccess());
        
        IterationStats.MethodStats method3 = stats.startMethod("m3", "P0");
        method3.complete("success", 100);  // 小写
        assertTrue(method3.isSuccess());
        
        IterationStats.MethodStats method4 = stats.startMethod("m4", "P0");
        method4.complete("FAILED", 0);
        assertFalse(method4.isSuccess());
        
        IterationStats.MethodStats method5 = stats.startMethod("m5", "P0");
        // 未调用 complete，默认状态是 "pending"
        assertFalse(method5.isSuccess());
    }

    @Test
    void testTotalTokensAccumulation() {
        // 测试 token 累计
        stats.recordPromptSize(100);
        stats.recordPromptSize(200);
        stats.recordResponseSize(50);
        stats.recordResponseSize(75);
        
        assertEquals(300, stats.getTotalPromptTokens());
        assertEquals(125, stats.getTotalResponseTokens());
    }

    @Test
    void testIterationCount() {
        // 测试迭代次数统计
        IterationStats.MethodStats method = stats.startMethod("testMethod", "P0");
        
        assertEquals(0, method.getIterationCount());
        
        method.incrementIteration();
        assertEquals(1, method.getIterationCount());
        
        method.incrementIteration();
        method.incrementIteration();
        assertEquals(3, method.getIterationCount());
    }

    @Test
    void testLargeMethodList() {
        // 测试大量方法的情况
        for (int i = 0; i < 20; i++) {
            IterationStats.MethodStats method = stats.startMethod("method" + i, i % 3 == 0 ? "P0" : (i % 3 == 1 ? "P1" : "P2"));
            method.addPromptTokens(100 + i * 10);
            method.addResponseTokens(50 + i * 5);
            method.complete(i % 4 == 0 ? "FAILED" : "SUCCESS", 60 + i * 2);
        }
        
        // 应该能正常生成报告
        String report = stats.generateMarkdownReport();
        
        assertNotNull(report);
        assertTrue(report.contains("method0"));
        assertTrue(report.contains("method19"));
        // 应该有趋势分析（因为超过 3 个方法）
        assertTrue(report.contains("Token") || report.contains("趋势"));
    }
}
