package com.Aryan.APIX.service;

import com.Aryan.APIX.model.FlowTrace;
import com.Aryan.APIX.model.TimingTrace;
import org.springframework.stereotype.Service;

@Service
public class FlowAnalyzerService {

    public FlowTrace analyze(TimingTrace trace){

        FlowTrace flow = new FlowTrace();
        long connectionTime = trace.getResponseReceived() - trace.getRequestStart();
        long responseTime = trace.getBodyParsed() - trace.getResponseReceived();
        long executionTime = trace.getTotalTime() - (connectionTime + responseTime);

        if (executionTime < 0) executionTime = 0;
        flow.setConnectionTime(connectionTime);
        flow.setExecutionTime(executionTime);
        flow.setResponseTime(responseTime);
        flow.setTotalTime(trace.getTotalTime());

        return flow;
    }
}