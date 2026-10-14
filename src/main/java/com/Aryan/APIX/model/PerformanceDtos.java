package com.Aryan.APIX.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class PerformanceDtos {
    private PerformanceDtos() { }

    public record CreateBaselineRequest(String name, List<Long> sampleIds) { }
    public record CreateComparisonRequest(List<Long> sampleIds) { }
    public record HistorySample(Long id, String executionId, String traceId, String method,
                                String url, int statusCode, long responseTime, LocalDateTime createdAt) { }
    public record MetricsSnapshot(Map<String, BigDecimal> metrics, int sampleCount,
                                   LocalDateTime windowStart, LocalDateTime windowEnd,
                                   Long windowDurationMs) { }
    public record MetricComparison(BigDecimal baselineValue, BigDecimal currentValue,
                                   BigDecimal absoluteDifference, BigDecimal percentageChange,
                                   BigDecimal threshold, String status, String note) { }
    public record Baseline(Long id, String name, String method, String url, int sampleCount,
                           LocalDateTime windowStart, LocalDateTime windowEnd,
                           Map<String, BigDecimal> metrics, LocalDateTime createdAt) { }
    public record RegressionReport(Long id, Long baselineId, String baselineName, String status,
                                   int sampleCount, List<Long> historyIds, List<String> executionIds, List<String> traceIds,
                                   MetricsSnapshot baselineMetrics, MetricsSnapshot currentMetrics,
                                   Map<String, MetricComparison> comparisons,
                                   Map<String, BigDecimal> thresholds,
                                   LocalDateTime createdAt) { }
    public record PageEnvelope<T>(List<T> content, int page, int size, long totalElements, int totalPages) { }
}
