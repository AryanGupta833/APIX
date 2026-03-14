package com.Aryan.APIX.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NetworkTimingContext {

    private long dnsStart;
    private long dnsEnd;

    private long tcpConnectStart;
    private long tcpConnectEnd;

    private long tlsHandshakeStart;
    private long tlsHandshakeEnd;

    private long requestSent;
    private long responseReceived;

    private long bodyReceived;
}
