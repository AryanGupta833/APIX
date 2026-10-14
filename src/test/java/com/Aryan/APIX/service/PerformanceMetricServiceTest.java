package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.model.PerformanceDtos.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceMetricServiceTest {
    private final PerformanceMetricService metrics = new PerformanceMetricService();

    @Test
    void aggregatesLatencyThroughputAndErrorRateAndOmitsUnsupportedPercentiles() {
        MetricsSnapshot result = metrics.aggregate(List.of(
                sample(10, 200, 0), sample(20, 500, 1000), sample(30, 200, 2000)));

        assertEquals(3, result.sampleCount());
        assertEquals("20.00", result.metrics().get("average_latency").toPlainString());
        assertEquals("20", result.metrics().get("p50_latency").toPlainString());
        assertFalse(result.metrics().containsKey("p95_latency"));
        assertEquals("1.500", result.metrics().get("throughput_rps").toPlainString());
        assertEquals("0.333333", result.metrics().get("error_rate").toPlainString());
    }

    @Test
    void usesNearestRankForHighPercentilesWhenThereAreEnoughSamples() {
        List<RequestHistory> samples = new ArrayList<>();
        for (int i = 1; i <= 20; i++) samples.add(sample(i, 200, i * 100));

        MetricsSnapshot result = metrics.aggregate(samples);

        assertEquals(19, result.metrics().get("p95_latency").intValue());
        assertEquals(20, result.metrics().get("p99_latency").intValue());
    }

    @Test
    void leavesThroughputUnavailableWhenTheMeasurementWindowIsZero() {
        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0);
        MetricsSnapshot result = metrics.aggregate(List.of(sample(2, 200, time), sample(3, 200, time)));

        assertNull(result.metrics().get("throughput_rps"));
        assertNull(result.windowDurationMs());
    }

    private RequestHistory sample(long latency, int status, long offsetMs) {
        return sample(latency, status, LocalDateTime.of(2026, 1, 1, 0, 0).plusNanos(offsetMs * 1_000_000));
    }

    private RequestHistory sample(long latency, int status, LocalDateTime createdAt) {
        RequestHistory history = new RequestHistory();
        history.setResponseTime(latency);
        history.setStatusCode(status);
        history.setCreatedAt(createdAt);
        return history;
    }
}
