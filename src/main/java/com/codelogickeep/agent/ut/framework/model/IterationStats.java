package com.codelogickeep.agent.ut.framework.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 迭代统计 - 跟踪每个方法的测试生成情况
 */
public class IterationStats {

    private final String targetFile;
    private final LocalDateTime startTime;
    private final List<MethodStats> methodStatsList = new ArrayList<>();
    private int totalPromptTokens = 0;
    private int totalResponseTokens = 0;
    private String feedbackSummary; // 覆盖率反馈历史

    public IterationStats(String targetFile) {
        this.targetFile = targetFile;
        this.startTime = LocalDateTime.now();
    }

    /**
     * 设置覆盖率反馈历史摘要
     */
    public void setFeedbackSummary(String summary) {
        this.feedbackSummary = summary;
    }

    /**
     * 获取覆盖率反馈历史摘要
     */
    public String getFeedbackSummary() {
        return feedbackSummary;
    }

    /**
     * 开始一个新方法的统计
     */
    public MethodStats startMethod(String methodName, String priority) {
        MethodStats stats = new MethodStats(methodName, priority);
        methodStatsList.add(stats);
        return stats;
    }

    /**
     * 开始一个新方法的统计（包含初始覆盖率）
     */
    public MethodStats startMethod(String methodName, String priority, double initialCoverage) {
        MethodStats stats = new MethodStats(methodName, priority, initialCoverage);
        methodStatsList.add(stats);
        return stats;
    }

    /**
     * 获取当前方法统计
     */
    public MethodStats getCurrentMethod() {
        if (methodStatsList.isEmpty()) {
            return null;
        }
        return methodStatsList.get(methodStatsList.size() - 1);
    }

    /**
     * 记录提示词大小（同时累加到当前方法和总计）
     */
    public void recordPromptSize(int tokens) {
        totalPromptTokens += tokens;
        MethodStats current = getCurrentMethod();
        if (current != null) {
            current.addPromptTokens(tokens);
        }
    }

    /**
     * 记录响应大小（同时累加到当前方法和总计）
     */
    public void recordResponseSize(int tokens) {
        totalResponseTokens += tokens;
        MethodStats current = getCurrentMethod();
        if (current != null) {
            current.addResponseTokens(tokens);
        }
    }
    
    /**
     * 仅累加到总计（当方法已单独累加时使用）
     */
    public void addToTotalPromptTokens(int tokens) {
        totalPromptTokens += tokens;
    }
    
    /**
     * 仅累加到总计（当方法已单独累加时使用）
     */
    public void addToTotalResponseTokens(int tokens) {
        totalResponseTokens += tokens;
    }

    /**
     * 生成 Markdown 报告
     */
    public String generateMarkdownReport() {
        LocalDateTime endTime = LocalDateTime.now();
        Duration duration = Duration.between(startTime, endTime);

        StringBuilder sb = new StringBuilder();
        sb.append("# 单元测试生成报告\n\n");

        // 基本信息
        sb.append("## 📋 基本信息\n\n");
        sb.append("| 项目 | 值 |\n");
        sb.append("|------|------|\n");
        sb.append("| **目标文件** | `").append(targetFile).append("` |\n");
        sb.append("| **开始时间** | ").append(startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append(" |\n");
        sb.append("| **结束时间** | ").append(endTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append(" |\n");
        sb.append("| **总耗时** | ").append(formatDuration(duration)).append(" |\n");
        sb.append("| **测试方法数** | ").append(methodStatsList.size()).append(" |\n");
        sb.append("\n");

        // Token 统计
        sb.append("## 📊 Token 使用统计\n\n");
        sb.append("| 指标 | 值 |\n");
        sb.append("|------|------|\n");
        sb.append("| **总提示词 Tokens** | ").append(String.format("%,d", totalPromptTokens)).append(" |\n");
        sb.append("| **总响应 Tokens** | ").append(String.format("%,d", totalResponseTokens)).append(" |\n");
        sb.append("| **总计 Tokens** | ").append(String.format("%,d", totalPromptTokens + totalResponseTokens))
                .append(" |\n");
        int methodCount = methodStatsList.size();
        if (methodCount > 0) {
            int avgTokens = (totalPromptTokens + totalResponseTokens) / methodCount;
            sb.append("| **平均每方法 Tokens** | ").append(String.format("%,d", avgTokens)).append(" |\n");
        }
        sb.append("\n");

        // 方法详情
        sb.append("## 🔍 方法测试详情\n\n");
        sb.append("| # | 方法名 | 优先级 | 初始覆盖率 | 最终覆盖率 | 迭代次数 | 状态 | Prompt Tokens | Response Tokens | 耗时 |\n");
        sb.append(
                "|---|--------|--------|------------|------------|----------|------|---------------|-----------------|------|\n");

        int index = 1;
        int successCount = 0;
        int failCount = 0;
        int skippedCount = 0;
        double totalCoverage = 0;

        for (MethodStats method : methodStatsList) {
            String statusEmoji;
            if (method.isSkipped()) {
                statusEmoji = "⏭️";
                skippedCount++;
            } else if (method.isSuccess()) {
                statusEmoji = "✅";
                successCount++;
            } else {
                statusEmoji = "❌";
                failCount++;
            }
            totalCoverage += method.getCoverage();

            // 计算覆盖率变化
            String coverageChange = "";
            if (method.getInitialCoverage() > 0 && method.getCoverage() > method.getInitialCoverage()) {
                coverageChange = String.format(" (+%.1f%%)", method.getCoverage() - method.getInitialCoverage());
            }

            sb.append("| ").append(index++).append(" ");
            sb.append("| `").append(method.getMethodName()).append("` ");
            sb.append("| ").append(method.getPriority()).append(" ");
            sb.append("| ").append(String.format("%.1f%%", method.getInitialCoverage())).append(" ");
            sb.append("| ").append(String.format("%.1f%%", method.getCoverage())).append(coverageChange).append(" ");
            sb.append("| ").append(method.getIterationCount()).append(" ");
            sb.append("| ").append(statusEmoji).append(" ").append(method.getStatus());
            if (method.getSkipReason() != null) {
                sb.append(" (").append(method.getSkipReason()).append(")");
            }
            sb.append(" ");
            sb.append("| ").append(String.format("%,d", method.getPromptTokens())).append(" ");
            sb.append("| ").append(String.format("%,d", method.getResponseTokens())).append(" ");
            sb.append("| ").append(formatDuration(method.getDuration())).append(" ");
            sb.append("|\n");
        }
        sb.append("\n");

        // 汇总统计
        sb.append("## 📈 汇总统计\n\n");
        sb.append("| 指标 | 值 |\n");
        sb.append("|------|------|\n");
        sb.append("| **成功方法** | ").append(successCount).append(" |\n");
        sb.append("| **跳过方法** | ").append(skippedCount).append(" (已达到覆盖率要求) |\n");
        sb.append("| **失败方法** | ").append(failCount).append(" |\n");
        sb.append("| **成功率** | ").append(String.format("%.1f%%",
                methodStatsList.isEmpty() ? 0 : ((successCount + skippedCount) * 100.0 / methodStatsList.size())))
                .append(" |\n");
        sb.append("| **平均最终覆盖率** | ").append(
                String.format("%.1f%%", methodStatsList.isEmpty() ? 0 : (totalCoverage / methodStatsList.size())))
                .append(" |\n");
        sb.append("\n");

        // Token 趋势分析
        if (methodStatsList.size() > 1) {
            sb.append("## 📉 Token 趋势分析\n\n");
            sb.append("```\n");

            int maxTokens = methodStatsList.stream()
                    .mapToInt(m -> m.getPromptTokens() + m.getResponseTokens())
                    .max().orElse(0);

            // 避免除零：如果所有方法都没有 token 记录，跳过图表
            if (maxTokens > 0) {
                for (MethodStats method : methodStatsList) {
                    int tokens = method.getPromptTokens() + method.getResponseTokens();
                    int barLength = (tokens * 40) / maxTokens;
                    String bar = "█".repeat(Math.max(1, barLength));
                    sb.append(String.format("%-20s │%s %,d\n",
                            truncate(method.getMethodName(), 20), bar, tokens));
                }
            } else {
                sb.append("(No token data recorded)\n");
            }
            sb.append("```\n\n");

            // 分析是否有下降趋势
            if (methodStatsList.size() >= 3) {
                int firstThreeCount = Math.min(3, methodStatsList.size());
                int lastThreeCount = Math.min(3, methodStatsList.size());

                int firstThreeSum = methodStatsList.subList(0, firstThreeCount).stream()
                        .mapToInt(m -> m.getPromptTokens())
                        .sum();
                int lastThreeSum = methodStatsList
                        .subList(Math.max(0, methodStatsList.size() - lastThreeCount), methodStatsList.size()).stream()
                        .mapToInt(m -> m.getPromptTokens())
                        .sum();

                // 避免除零
                if (firstThreeCount > 0 && firstThreeSum > 0) {
                    int firstThreeAvg = firstThreeSum / firstThreeCount;
                    int lastThreeAvg = lastThreeSum / lastThreeCount;

                    if (lastThreeAvg < firstThreeAvg && firstThreeAvg > 0) {
                        int reduction = (firstThreeAvg - lastThreeAvg) * 100 / firstThreeAvg;
                        sb.append("✅ **Token 使用下降趋势**: 后期方法平均比前期减少 **").append(reduction).append("%**\n\n");
                    } else {
                        sb.append("ℹ️ Token 使用保持稳定，未观察到明显下降趋势\n\n");
                    }
                }
            }
        }

        // 覆盖率反馈历史
        if (feedbackSummary != null && !feedbackSummary.isEmpty() && !feedbackSummary.startsWith("No feedback")) {
            sb.append("## 📈 覆盖率反馈历史\n\n");
            sb.append("```\n");
            sb.append(feedbackSummary);
            sb.append("```\n\n");
        }

        sb.append("---\n");
        sb.append("*报告生成时间: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("*\n");

        return sb.toString();
    }

    /**
     * 保存报告到文件
     */
    public void saveReport(Path projectRoot) {
        try {
            String report = generateMarkdownReport();
            String fileName = "test-generation-report-" +
                    startTime.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md";
            Path reportPath = projectRoot.resolve(fileName);
            Files.writeString(reportPath, report);
            System.out.println("\n📄 报告已保存: " + reportPath);
        } catch (Exception e) {
            System.err.println("保存报告失败: " + e.getMessage());
            e.printStackTrace(); // 打印完整堆栈以便调试
        }
    }

    private String formatDuration(Duration duration) {
        if (duration == null) {
            return "N/A";
        }
        long minutes = duration.toMinutes();
        long seconds = duration.getSeconds() % 60;
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        }
        return String.format("%ds", seconds);
    }

    private String truncate(String s, int maxLen) {
        if (s == null)
            return "";
        if (s.length() <= maxLen)
            return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    // Getters
    public List<MethodStats> getMethodStatsList() {
        return methodStatsList;
    }

    public int getTotalPromptTokens() {
        return totalPromptTokens;
    }

    public int getTotalResponseTokens() {
        return totalResponseTokens;
    }

    /**
     * 单个方法的统计
     */
    public static class MethodStats {
        private String methodName;
        private String priority;
        private double initialCoverage = 0; // 初始覆盖率
        private final LocalDateTime startTime;
        private LocalDateTime endTime;
        private int iterationCount = 0;
        private String status = "pending";
        private double coverage = 0;
        private int promptTokens = 0;
        private int responseTokens = 0;
        private boolean skipped = false; // 是否跳过（已达到覆盖率要求）
        private String skipReason = null; // 跳过原因

        public MethodStats(String methodName, String priority) {
            this.methodName = methodName;
            this.priority = priority;
            this.startTime = LocalDateTime.now();
        }

        public MethodStats(String methodName, String priority, double initialCoverage) {
            this.methodName = methodName;
            this.priority = priority;
            this.initialCoverage = initialCoverage;
            this.startTime = LocalDateTime.now();
        }

        /**
         * 更新方法名（当从 LLM 响应中提取到实际方法名时）
         */
        public void setMethodName(String methodName) {
            this.methodName = methodName;
        }

        /**
         * 更新优先级
         */
        public void setPriority(String priority) {
            this.priority = priority;
        }

        /**
         * 标记为跳过
         */
        public void markSkipped(String reason) {
            this.skipped = true;
            this.skipReason = reason;
            this.status = "SKIPPED";
            this.endTime = LocalDateTime.now();
        }

        public void incrementIteration() {
            iterationCount++;
        }

        public void complete(String status, double coverage) {
            this.status = status;
            this.coverage = coverage;
            this.endTime = LocalDateTime.now();
        }

        public void addPromptTokens(int tokens) {
            this.promptTokens += tokens;
        }

        public void addResponseTokens(int tokens) {
            this.responseTokens += tokens;
        }

        public boolean isSuccess() {
            return "SUCCESS".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)
                    || "SKIPPED".equalsIgnoreCase(status);
        }

        public Duration getDuration() {
            if (endTime == null) {
                return Duration.between(startTime, LocalDateTime.now());
            }
            return Duration.between(startTime, endTime);
        }

        // Getters
        public String getMethodName() {
            return methodName;
        }

        public String getPriority() {
            return priority;
        }

        public int getIterationCount() {
            return iterationCount;
        }

        public String getStatus() {
            return status;
        }

        public double getCoverage() {
            return coverage;
        }

        public int getPromptTokens() {
            return promptTokens;
        }

        public int getResponseTokens() {
            return responseTokens;
        }

        public double getInitialCoverage() {
            return initialCoverage;
        }

        public boolean isSkipped() {
            return skipped;
        }

        public String getSkipReason() {
            return skipReason;
        }
    }
}
