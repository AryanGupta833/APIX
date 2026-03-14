package com.Aryan.APIX.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ApiResponse {
    private int statusCode;
    private Map<String,String> headers;
    private String body;
    private long responseTime;
}
