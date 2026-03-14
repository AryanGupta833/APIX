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
public class ApiRequest {
    private String method;
    private String url;
    private Map<String, String> headers;
    private String body;
    private Map<String,String> queryParams;
    private boolean diagnosticMode;


}
