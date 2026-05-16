package com.Aryan.APIX.ai.dto;

import lombok.Data;

import java.util.Map;

@Data
public class AIRequest {
    private String requestUrl;
    private String method;
    private int statusCode;
    private String responseBody;
    private Map<String,String> headers;
    private String trace;
    private long latency;
}
