package com.Aryan.APIX.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TimingTrace {
    private long requestStart;
    private Long responseReceived;
    private Long bodyParsed;
    private long totalTime;
    private Long serverProcessingTime;
    private Long downloadTime;
    private Long connectionTime;
    private Long executionTime;

}
