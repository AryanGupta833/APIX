package com.Aryan.APIX.service;

import com.Aryan.APIX.model.ApiEndPoint;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OpenApiImportService {
    public List<ApiEndPoint> parse(String spec){
        SwaggerParseResult result=new OpenAPIV3Parser().readContents(spec);

        OpenAPI openAPI=result.getOpenAPI();

        List<ApiEndPoint> endPoints=new ArrayList<>();
        if(openAPI==null){
            return endPoints;
        }
        Map<String, PathItem> paths=openAPI.getPaths();
        paths.forEach((path,pathItem)->{
            pathItem.readOperationsMap().forEach((method,operation)->{
                ApiEndPoint ep=new ApiEndPoint();
                ep.setMethod(method.toString().toUpperCase());
                ep.setPath(path);

                if(operation.getSummary()!=null){
                    ep.setSummary(operation.getSummary());
                }
                endPoints.add(ep);
            });
        });
        return endPoints;
    }
 }
