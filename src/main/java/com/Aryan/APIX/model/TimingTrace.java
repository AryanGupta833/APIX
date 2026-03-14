package com.Aryan.APIX.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TimingTrace {
    private long requestStart;
    private long responseReceived;
    private long bodyParsed;
    private long totalTime;
    private long serverProcessingTime;
    private long downloadTime;

}
