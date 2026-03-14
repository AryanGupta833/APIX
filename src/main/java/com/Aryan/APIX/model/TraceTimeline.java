package com.Aryan.APIX.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TraceTimeline {
    private List<TimelineSpan> spans;

}
