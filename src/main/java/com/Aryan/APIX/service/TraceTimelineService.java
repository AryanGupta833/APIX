package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.model.TimelineSpan;
import com.Aryan.APIX.model.TraceTimeline;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TraceTimelineService {

    public TraceTimeline build(List<RequestHistory> history){

        List<TimelineSpan> spans = new ArrayList<>();

        for(RequestHistory exec : history){

            if(exec.getCreatedAt() == null){
                continue;
            }

            long latency = exec.getResponseTime();

            if(latency <= 0 || latency > 60000){
                continue;
            }

            long startTime = exec.getCreatedAt()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();

            TimelineSpan span = new TimelineSpan();

            span.setService(exec.getUrl());
            span.setStartTime(startTime);
            span.setEndTime(startTime + latency);
            span.setDuration(latency);

            spans.add(span);
        }

        TraceTimeline traceTimeline = new TraceTimeline();
        traceTimeline.setSpans(spans);

        return traceTimeline;
    }
}
