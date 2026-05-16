package com.Aryan.APIX.ai.service;

import com.Aryan.APIX.ai.dto.AIRequest;
import com.Aryan.APIX.ai.dto.AIResponse;
import com.Aryan.APIX.ai.provider.AIProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.Key;

@Service
public class AIAnalysisService {

    @Autowired
    private AIProvider aiProvider;

    public AIResponse analyzeFailure(AIRequest request){
        sanitizeRequest(request);
        return aiProvider.analyze(request);
    }

    public void sanitizeRequest(AIRequest aiRequest){
        if(aiRequest.getHeaders()==null){
            return ;
        }

        aiRequest.getHeaders().replaceAll((key,value)->{
            String lowerKey= key.toLowerCase();
            if(lowerKey.contains("authorization")||
                    lowerKey.contains("cookie")
                    ||lowerKey.contains("token")||
                    lowerKey.contains("secret")||
                    lowerKey.contains("password"))
            {
                return "********";
            }
            return value;
        });
    }
}
