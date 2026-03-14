package com.Aryan.APIX.model;


import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoadTestResult {

    private int totalRequests;
    private int successCount;
    private int failureCount;
    private long averageLatency;
    private long maxLatency;
}
