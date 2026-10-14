package com.Aryan.APIX.service;

import com.Aryan.APIX.model.FlowTrace;
import com.Aryan.APIX.model.TimingTrace;
import org.springframework.stereotype.Service;

@Service
public class FlowAnalyzerService {

    public FlowTrace analyze(TimingTrace trace){

        FlowTrace flow = new FlowTrace();
        long connectionTime = nonNegative(trace.getConnectionTime());
        long executionTime = nonNegative(trace.getExecutionTime());
        long responseTime = nonNegative(trace.getDownloadTime());
        flow.setConnectionTime(connectionTime);
        flow.setExecutionTime(executionTime);
        flow.setResponseTime(responseTime);
        flow.setTotalTime(trace.getTotalTime());

        return flow;
    }

    private long nonNegative(Long duration) {
        return duration == null ? 0 : Math.max(0, duration);
    }
}
