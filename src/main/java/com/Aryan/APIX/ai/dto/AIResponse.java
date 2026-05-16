package com.Aryan.APIX.ai.dto;

import lombok.Data;

import java.util.List;

@Data
public class AIResponse {
    private String failureType;
    private String layer;
    private String reason;
    private List<String> fixes;
    private double confidence;

}
