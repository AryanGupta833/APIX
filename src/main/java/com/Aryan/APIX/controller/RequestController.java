package com.Aryan.APIX.controller;

import com.Aryan.APIX.model.ApiExecutionResponse;
import com.Aryan.APIX.model.ApiRequest;
import com.Aryan.APIX.service.RequestExecutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/apix")
public class RequestController {
    @Autowired
    private RequestExecutionService requestExecutionService;

    @PostMapping("/request")
    public ApiExecutionResponse executeResult(@RequestBody ApiRequest request){
        return requestExecutionService.execute(request);
    }

}
