package com.Aryan.APIX.service;

import com.Aryan.APIX.model.ApiExecutionResponse;
import com.Aryan.APIX.model.ApiRequest;
import com.Aryan.APIX.model.ApiResponse;
import com.Aryan.APIX.model.LoadTestResult;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LoadTestService {
    @Autowired
    private RequestExecutionService requestExecutionService;

    @Value("${apix.outbound.max-load-test-requests:50}")
    private int maxRequests;

    public LoadTestResult run(ApiRequest request,int count){
        if (maxRequests < 1 || maxRequests > 200) throw new IllegalStateException("Invalid load-test request limit configuration");
        if (count < 1 || count > maxRequests) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Load test request count must be between 1 and " + maxRequests);
        long totalLatency=0;
        long maxLatency=0;

        int success=0;
        int failure=0;

        for(int i=0;i<count;i++){
            ApiExecutionResponse res=requestExecutionService.execute(request);

            long latency=res.getApiResponse().getResponseTime();

            totalLatency+=latency;
            maxLatency=Math.max(maxLatency,latency);

            if(res.getApiResponse().getResponseTime()<400){
                success++;
            }
            else {
                failure++;
            }

        }
        LoadTestResult result=new LoadTestResult();

        result.setTotalRequests(count);
        result.setSuccessCount(success);
        result.setFailureCount((int)totalLatency/count);
        result.setMaxLatency(maxLatency);

        return  result;
    }
}
