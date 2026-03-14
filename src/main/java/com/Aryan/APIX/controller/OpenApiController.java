package com.Aryan.APIX.controller;

import com.Aryan.APIX.model.ApiEndPoint;
import com.Aryan.APIX.service.OpenApiImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/apix/openapi")
public class OpenApiController {

    @Autowired
    private OpenApiImportService openApiImportService;

    @PostMapping("/import")
    public List<ApiEndPoint> importSpec(@RequestBody String spec){
        return openApiImportService.parse(spec);
    }
}
