package com.Aryan.APIX.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ApiExecutionResponse {

    private ApiResponse apiResponse;

    private ErrorAnalysis errorAnalysis;

    private XRayAnalysis xRayAnalysis;

    private TimingTrace timingTrace;

    private KnowledgeAnalysis knowledgeAnalysis;
    private FlowTrace flowTrace;

    private FlowGraph flowGraph;

    private DependencyGraph dependencyGraph;

    private TraceTimeline traceTimeline;
    private String traceId;

}
