package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.model.FailurePattern;
import com.Aryan.APIX.model.KnowledgeAnalysis;
import com.Aryan.APIX.model.PerformanceInsight;
import com.Aryan.APIX.repository.HistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeAnalyzerService {

    @Autowired
    private HistoryRepository historyRepository;

    @Autowired
    private SmartFailureDetectorService smartFailureDetectorService;

    @Autowired
    private PerformanceAnalyzerService performanceAnalyzerService;

    public KnowledgeAnalysis analysis(String url){

        List<RequestHistory> history = historyRepository.findByUrl(url);

        KnowledgeAnalysis result = new KnowledgeAnalysis();

        if(history.isEmpty()){
            result.setDiagnosis("Not enough history data");
            result.setSuggestions("Run API multiple times for deeper analysis");
            return result;
        }

        int failures = 0;
        long totalLatency = 0;
        int validCount = 0;

        for(RequestHistory exec : history){

            long latency = exec.getResponseTime();


            if(latency <= 0 || latency > 60000){
                continue;
            }

            totalLatency += latency;
            validCount++;

            if(exec.getStatusCode() >= 400){
                failures++;
            }
        }

        double failureRate = (failures * 100.0) / history.size();

        long avgLatency = validCount == 0 ? 0 : totalLatency / validCount;

        result.setFailureRate(failureRate);
        result.setAverageLatency(avgLatency);

        if(failureRate > 50){
            result.setDiagnosis("API appears unstable");
            result.setSuggestions("High failure rate detected");
        }
        else if(avgLatency > 1000){
            result.setDiagnosis("Performance degradation detected");
            result.setSuggestions("Average latency is high");
        }
        else{
            result.setDiagnosis("API behavior appears stable");
            result.setSuggestions("No major reliability issues detected");
        }

        FailurePattern pattern = smartFailureDetectorService.detect(history);
        result.setFailurePattern(pattern);

        PerformanceInsight insight = performanceAnalyzerService.analyze(history);
        result.setPerformanceInsight(insight);

        return result;
    }
}
