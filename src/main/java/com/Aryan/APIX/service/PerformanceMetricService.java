package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.model.PerformanceDtos.MetricsSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PerformanceMetricService {
    private static final int HIGH_PERCENTILE_MIN_SAMPLES = 20;

    @Value("${apix.performance.minimum-samples:5}")
    private int minimumSamples = 5;

    public int getMinimumSamples() { return Math.max(2, minimumSamples); }

    public MetricsSnapshot aggregate(List<RequestHistory> executions) {
        List<RequestHistory> samples = executions.stream()
                .filter(sample -> sample.getCreatedAt() != null && sample.getStatusCode() > 0 && sample.getResponseTime() >= 0)
                .sorted(Comparator.comparing(RequestHistory::getCreatedAt))
                .toList();

        Map<String, BigDecimal> values = new LinkedHashMap<>();
        if (samples.isEmpty()) return new MetricsSnapshot(values, 0, null, null, null);

        List<Long> latencies = samples.stream().map(RequestHistory::getResponseTime).sorted().toList();
        BigDecimal sum = latencies.stream().map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add);
        values.put("average_latency", sum.divide(BigDecimal.valueOf(latencies.size()), 2, RoundingMode.HALF_UP));
        values.put("p50_latency", BigDecimal.valueOf(nearestRank(latencies, 0.50)));
        if (latencies.size() >= HIGH_PERCENTILE_MIN_SAMPLES) {
            values.put("p95_latency", BigDecimal.valueOf(nearestRank(latencies, 0.95)));
            values.put("p99_latency", BigDecimal.valueOf(nearestRank(latencies, 0.99)));
        }

        long failures = samples.stream().filter(sample -> sample.getStatusCode() >= 400).count();
        values.put("error_rate", BigDecimal.valueOf(failures)
                .divide(BigDecimal.valueOf(samples.size()), 6, RoundingMode.HALF_UP));

        LocalDateTime start = samples.get(0).getCreatedAt();
        LocalDateTime end = samples.get(samples.size() - 1).getCreatedAt();
        long durationMs = Duration.between(start, end).toMillis();
        if (durationMs > 0) {
            values.put("throughput_rps", BigDecimal.valueOf(samples.size())
                    .multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(durationMs), 3, RoundingMode.HALF_UP));
        }
        return new MetricsSnapshot(values, samples.size(), start, end, durationMs > 0 ? durationMs : null);
    }

    private long nearestRank(List<Long> sortedValues, double percentile) {
        int rank = (int) Math.ceil(percentile * sortedValues.size());
        return sortedValues.get(Math.max(0, rank - 1));
    }
}
