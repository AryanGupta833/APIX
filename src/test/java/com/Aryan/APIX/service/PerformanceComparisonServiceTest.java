package com.Aryan.APIX.service;

import com.Aryan.APIX.model.PerformanceDtos.MetricComparison;
import com.Aryan.APIX.model.PerformanceDtos.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerformanceComparisonServiceTest {
    private final PerformanceComparisonService comparison = new PerformanceComparisonService();

    @Test
    void classifiesLatencyThroughputAndErrorRateRegressions() {
        MetricsSnapshot baseline = snapshot("100", "10", "0");
        MetricsSnapshot current = snapshot("115", "8", "0.02");

        Map<String, MetricComparison> result = comparison.compare(baseline, current);

        assertEquals("REGRESSION", result.get("average_latency").status());
        assertEquals("REGRESSION", result.get("throughput_rps").status());
        assertEquals("REGRESSION", result.get("error_rate").status());
        assertEquals("REGRESSION", comparison.overallStatus(result));
    }

    @Test
    void distinguishesImprovementUnchangedAndUnavailableMetrics() {
        Map<String, MetricComparison> improved = comparison.compare(snapshot("100", "10", "0.02"), snapshot("80", "11.2", "0.005"));
        assertEquals("IMPROVEMENT", improved.get("average_latency").status());
        assertEquals("IMPROVEMENT", improved.get("throughput_rps").status());
        assertEquals("IMPROVEMENT", improved.get("error_rate").status());

        Map<String, MetricComparison> unchanged = comparison.compare(snapshot("100", "10", "0"), snapshot("105", "10.5", "0.005"));
        assertEquals("UNCHANGED", unchanged.get("average_latency").status());
        assertEquals("UNCHANGED", unchanged.get("throughput_rps").status());
        assertEquals("UNCHANGED", unchanged.get("error_rate").status());
    }

    @Test
    void safelyHandlesZeroBaselineAndMissingHighPercentiles() {
        MetricsSnapshot baseline = new MetricsSnapshot(Map.of("average_latency", bd("0"), "error_rate", bd("0")), 5, null, null, null);
        MetricsSnapshot current = new MetricsSnapshot(Map.of("average_latency", bd("5"), "error_rate", bd("0.02")), 5, null, null, null);

        Map<String, MetricComparison> result = comparison.compare(baseline, current);
        assertEquals("UNAVAILABLE", result.get("average_latency").status());
        assertEquals("REGRESSION", result.get("error_rate").status());
        assertEquals("UNAVAILABLE", result.get("p95_latency").status());
        assertEquals("REGRESSION", comparison.overallStatus(result));
    }

    private MetricsSnapshot snapshot(String latency, String throughput, String errors) {
        return new MetricsSnapshot(Map.of("average_latency", bd(latency), "p50_latency", bd(latency),
                "throughput_rps", bd(throughput), "error_rate", bd(errors)), 20, null, null, 1000L);
    }

    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
