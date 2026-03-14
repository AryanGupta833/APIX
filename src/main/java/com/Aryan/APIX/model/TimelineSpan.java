package com.Aryan.APIX.model;


import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TimelineSpan {

    private String service;
    private long StartTime;
    private long endTime;
    private long duration;
}
