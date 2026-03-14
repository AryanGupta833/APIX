package com.Aryan.APIX.model;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.boot.internal.Abstract;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionTrace {

    private String status;
    private int statusCode;
    private long totalTime;

    private long dnsTime;
    private long tcpTime;
    private long tlsTime;
    private long serverTime;
    private long transferTime;

    private String failureLayer;
    private String failureReason;
}
