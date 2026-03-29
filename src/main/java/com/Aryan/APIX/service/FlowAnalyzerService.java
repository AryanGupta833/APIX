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

        long newtowrkTime=flow.getTotalTime()-(serverTime+transferTime);
        if(newtowrkTime<0)newtowrkTime=0;
        long dns=newtowrkTime/3;
        long tcp=newtowrkTime/3;
        long tls=newtowrkTime-dns-tcp;
        flow.setDnsTime(dns);
        flow.setTcpConnectTime(tcp);
        flow.setTlsHandshakeTime(tls);

        return  flow;
    }
}
