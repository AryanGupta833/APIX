package com.Aryan.APIX.model;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FlowTrace {

    private long connectionTime;
    private long executionTime;
    private long responseTime;
    private long totalTime;

}
