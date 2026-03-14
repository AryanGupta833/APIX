package com.Aryan.APIX.service;

import com.Aryan.APIX.model.FlowTrace;
import com.Aryan.APIX.model.TimingTrace;
import org.springframework.stereotype.Service;

import java.util.concurrent.Flow;

@Service
public class FlowAnalyzerService {
    public FlowTrace analyze(TimingTrace trace){
        FlowTrace flow=new FlowTrace();

        long serverTime=trace.getResponseReceived()-trace.getRequestStart();
        long transferTime=trace.getBodyParsed()-trace.getResponseReceived();

        flow.setServerProcessingTime(serverTime);
        flow.setResponseTransferTime(transferTime);
        flow.setTotalTime(trace.getTotalTime());

        flow.setDnsTime(5);
        flow.setTcpConnectTime(10);
        flow.setTlsHandshakeTime(15);

        return  flow;
    }
}
