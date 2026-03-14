package com.Aryan.APIX.model;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FlowTrace {

    private long dnsTime;
    private long tcpConnectTime;
    private long tlsHandshakeTime;
    private long serverProcessingTime;
    private long responseTransferTime;
    private long totalTime;

}
