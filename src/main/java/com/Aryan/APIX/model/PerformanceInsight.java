package com.Aryan.APIX.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PerformanceInsight {

    private long averageLatency;
    private long maxLatency;
    private long minLatency;
    private String performanceStatus;
    private String recommendation;
}
