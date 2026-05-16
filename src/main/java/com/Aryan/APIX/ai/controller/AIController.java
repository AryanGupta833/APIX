package com.Aryan.APIX.ai.controller;


import com.Aryan.APIX.ai.dto.AIRequest;
import com.Aryan.APIX.ai.dto.AIResponse;
import com.Aryan.APIX.ai.service.AIAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AIController {
    @Autowired
    private AIAnalysisService aiAnalysisService;

    @PostMapping("/analyze")
    public ResponseEntity<AIResponse> analyzeFailure(@RequestBody AIRequest aiRequest){
        AIResponse response=aiAnalysisService.analyzeFailure(aiRequest);
        return  ResponseEntity.ok(response);
    }
}
