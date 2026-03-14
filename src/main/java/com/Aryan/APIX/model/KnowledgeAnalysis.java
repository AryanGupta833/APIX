package com.Aryan.APIX.model;


import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class KnowledgeAnalysis {
    private double failureRate;
    private long averageLatency;
    private String diagnosis;
    private String suggestions;
    private FailurePattern failurePattern;
    private PerformanceInsight performanceInsight;
}
