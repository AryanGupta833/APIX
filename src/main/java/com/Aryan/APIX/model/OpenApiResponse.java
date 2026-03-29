package com.Aryan.APIX.model;

import lombok.Getter;

import java.util.List;

@Getter
public class OpenApiResponse {
    private String baseUrl;
    private List<ApiEndPoint> endpoints;

    public OpenApiResponse(String baseUrl,List<ApiEndPoint> endpoints){
        this.baseUrl=baseUrl;
        this.endpoints=endpoints;}

}
