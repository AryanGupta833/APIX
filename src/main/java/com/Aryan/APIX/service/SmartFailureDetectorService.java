package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.model.FailurePattern;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SmartFailureDetectorService {
    public FailurePattern detect(List<RequestHistory> history){
        FailurePattern pattern=new FailurePattern();

        int serverErrors=0;
        int rateLimitErrors=0;

        for(RequestHistory exec:history){
            int status=exec.getStatusCode();

            if(status>=500){
                serverErrors++;
            }
            if(status==429){
                rateLimitErrors++;
            }
        }

        if(rateLimitErrors>=3){
            pattern.setPatternType("Rate Limiting");

            pattern.setDescription("API frequently returns 429 too many requests");
            pattern.setRecommendation("Reduce request frequently or implement retry backoff");

            return pattern;
        }

        if(serverErrors>=5){
            pattern.setPatternType("Backend Instability");

            pattern.setDescription("Multiple server errors detected in recent executions");

            pattern.setRecommendation("Check backend service health or logs");
            return pattern;
        }

        pattern.setPatternType("No pattern detected");
        pattern.setDescription("No significant failure pattern detected");
        pattern.setRecommendation("API behavior appears normal");

        return pattern;


    }
}
