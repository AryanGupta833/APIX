package com.Aryan.APIX.service;

import com.Aryan.APIX.model.TimingTrace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowAnalyzerServiceTest {
    private final FlowAnalyzerService service = new FlowAnalyzerService();

    @Test
    void usesMeasuredPhaseDurationsInsteadOfSubtractingWallAndMonotonicTimestamps() {
        TimingTrace trace = new TimingTrace();
        trace.setRequestStart(1_760_000_000_000L); // wall-clock epoch milliseconds
        trace.setResponseReceived(8_500_000L);     // monotonic milliseconds
        trace.setBodyParsed(8_500_003L);
        trace.setTotalTime(42L);
        trace.setConnectionTime(4L);
        trace.setExecutionTime(35L);
        trace.setDownloadTime(3L);

        var flow = service.analyze(trace);

        assertEquals(4L, flow.getConnectionTime());
        assertEquals(35L, flow.getExecutionTime());
        assertEquals(3L, flow.getResponseTime());
        assertEquals(42L, flow.getTotalTime());
    }

    @Test
    void treatsMissingOrNegativePhasesAsZero() {
        TimingTrace trace = new TimingTrace();
        trace.setTotalTime(20L);
        trace.setConnectionTime(-1L);
        trace.setExecutionTime(null);
        trace.setDownloadTime(5L);

        var flow = service.analyze(trace);

        assertEquals(0L, flow.getConnectionTime());
        assertEquals(0L, flow.getExecutionTime());
        assertEquals(5L, flow.getResponseTime());
    }
}
