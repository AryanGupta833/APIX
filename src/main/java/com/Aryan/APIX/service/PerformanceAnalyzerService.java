package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.model.PerformanceInsight;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PerformanceAnalyzerService {
    public PerformanceInsight analyze(List<RequestHistory> history){
        PerformanceInsight insight=new PerformanceInsight();

        if(history.isEmpty()){
            insight.setPerformanceStatus("No data");
            insight.setRecommendation("Execute API multiple time to analyze performance");
            return  insight;
        }

        long total=0;
        long max=Long.MIN_VALUE;
        long min=Long.MAX_VALUE;
        int validCount=0;

        for(RequestHistory exec:history){
            long latency=exec.getResponseTime();

            if(latency <= 0 || latency > 60000){
                continue;
            }

            total+=latency;
            validCount++;
            if(latency>max){
                max=latency;
            }
            if(latency<min){
                min=latency;
            }

        }

        if(validCount == 0){
            insight.setPerformanceStatus("Insufficient valid latency data");
            insight.setRecommendation("Run API again to collect valid performance metrics");
            return insight;
        }


        long avg=total/history.size();

        insight.setAverageLatency(avg);
        insight.setMaxLatency(max);
        insight.setMinLatency(min);

        if(avg>100){
            insight.setPerformanceStatus("Slow API");
            insight.setRecommendation("Average latency is high");
        }

        else if(max>avg*3){
            insight.setPerformanceStatus("Latency spike detected");
            insight.setRecommendation("Some executions are much slower than normal");

        }

        else {
            insight.setPerformanceStatus("Stable Perfomance");
            insight.setRecommendation("API perfomance appears consitent");

        }

        return insight;
    }
}
