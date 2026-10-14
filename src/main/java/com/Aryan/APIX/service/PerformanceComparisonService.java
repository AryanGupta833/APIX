package com.Aryan.APIX.service;

import com.Aryan.APIX.model.PerformanceDtos.MetricComparison;
import com.Aryan.APIX.model.PerformanceDtos.MetricsSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PerformanceComparisonService {
    @Value("${apix.performance.latency-regression-threshold:0.10}")
    private BigDecimal latencyThreshold = new BigDecimal("0.10");
    @Value("${apix.performance.throughput-regression-threshold:0.10}")
    private BigDecimal throughputThreshold = new BigDecimal("0.10");
    @Value("${apix.performance.error-rate-increase-threshold:0.01}")
    private BigDecimal errorRateThreshold = new BigDecimal("0.01");

    public Map<String, BigDecimal> thresholds() {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("latency_relative", latencyThreshold);
        values.put("throughput_relative", throughputThreshold);
        values.put("error_rate_absolute", errorRateThreshold);
        return values;
    }

    public Map<String, MetricComparison> compare(MetricsSnapshot baseline, MetricsSnapshot current) {
        Map<String, MetricComparison> results = new LinkedHashMap<>();
        List.of("average_latency", "p50_latency", "p95_latency", "p99_latency", "throughput_rps", "error_rate")
                .forEach(metric -> results.put(metric, compareMetric(metric, baseline.metrics().get(metric), current.metrics().get(metric))));
        return results;
    }

    public String overallStatus(Map<String, MetricComparison> comparisons) {
        List<String> statuses = comparisons.values().stream().map(MetricComparison::status)
                .filter(status -> !"UNAVAILABLE".equals(status)).toList();
        if (statuses.isEmpty()) return "INCONCLUSIVE";
        if (statuses.contains("REGRESSION")) return "REGRESSION";
        if (statuses.contains("IMPROVEMENT")) return "IMPROVEMENT";
        return "NO_REGRESSION";
    }

    private MetricComparison compareMetric(String metric, BigDecimal baseline, BigDecimal current) {
        BigDecimal threshold = metric.equals("error_rate") ? errorRateThreshold
                : metric.equals("throughput_rps") ? throughputThreshold : latencyThreshold;
        if (baseline == null || current == null) {
            return new MetricComparison(baseline, current, null, null, threshold, "UNAVAILABLE",
                    "A measurement is missing or does not meet its sample requirement.");
        }
        BigDecimal absolute = current.subtract(baseline).setScale(6, RoundingMode.HALF_UP);
        BigDecimal pct = baseline.signum() == 0 ? null
                : absolute.multiply(BigDecimal.valueOf(100)).divide(baseline.abs(), 4, RoundingMode.HALF_UP);
        boolean errorRate = metric.equals("error_rate");
        if (!errorRate && baseline.signum() == 0) {
            return new MetricComparison(baseline, current, absolute, null, threshold, "UNAVAILABLE",
                    "Relative change is undefined for a zero baseline.");
        }

        int direction = errorRate ? absolute.compareTo(errorRateThreshold)
                : metric.equals("throughput_rps") ? pct.compareTo(throughputThreshold.multiply(BigDecimal.valueOf(-100)))
                : pct.compareTo(latencyThreshold.multiply(BigDecimal.valueOf(100)));
        int improvementDirection = errorRate ? absolute.compareTo(errorRateThreshold.negate())
                : metric.equals("throughput_rps") ? pct.compareTo(throughputThreshold.multiply(BigDecimal.valueOf(100)))
                : pct.compareTo(latencyThreshold.multiply(BigDecimal.valueOf(-100)));

        String status = "UNCHANGED";
        if (metric.equals("throughput_rps")) {
            if (direction < 0) status = "REGRESSION";
            else if (improvementDirection > 0) status = "IMPROVEMENT";
        } else {
            if (direction > 0) status = "REGRESSION";
            else if (improvementDirection < 0) status = "IMPROVEMENT";
        }
        BigDecimal displayedThreshold = errorRate ? errorRateThreshold : threshold.multiply(BigDecimal.valueOf(100));
        return new MetricComparison(baseline, current, absolute, pct, displayedThreshold, status,
                "Threshold-based classification; statistical significance is not assessed.");
    }
}
