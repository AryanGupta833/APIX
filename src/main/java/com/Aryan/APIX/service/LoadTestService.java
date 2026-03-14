package com.Aryan.APIX.service;

import com.Aryan.APIX.model.ApiExecutionResponse;
import com.Aryan.APIX.model.ApiRequest;
import com.Aryan.APIX.model.ApiResponse;
import com.Aryan.APIX.model.LoadTestResult;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LoadTestService {
    @Autowired
    private RequestExecutionService requestExecutionService;

    public LoadTestResult run(ApiRequest request,int count){
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
