package com.Aryan.APIX.ai.service;

import com.Aryan.APIX.ai.dto.AIRequest;
import com.Aryan.APIX.ai.dto.AIResponse;
import com.Aryan.APIX.ai.provider.AIProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.util.Collection;
import com.Aryan.APIX.service.SensitiveDataRedactor;

@Service
public class AIAnalysisService {

    @Autowired
    private AIProvider aiProvider;

    @Autowired
    private SensitiveDataRedactor redactor;

    public AIResponse analyzeFailure(AIRequest request){
        return analyzeFailure(request, java.util.List.of());
    }

    public AIResponse analyzeFailure(AIRequest request, Collection<String> additionalSecrets){
        request.setHeaders(redactor.redactHeaders(request.getHeaders(), additionalSecrets));
        request.setRequestUrl(redactor.redactText(redactor.redactUrl(request.getRequestUrl()), additionalSecrets));
        request.setResponseBody(redactor.redactText(request.getResponseBody(), additionalSecrets));
        request.setTrace(redactor.redactText(request.getTrace(), additionalSecrets));
        return aiProvider.analyze(request);
    }

    public void sanitizeRequest(AIRequest aiRequest){
        aiRequest.setHeaders(redactor.redactHeaders(aiRequest.getHeaders()));
    }
}
