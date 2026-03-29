package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RequestExecutionService {

    @Autowired
    private WebClient webClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TraceTimelineService traceTimelineService;

    @Autowired
    private DependencyGraphBuilderService dependencyGraphBuilderService;

    @Autowired
    private FlowGraphBuilderService flowGraphBuilderService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ErrorAnalysisService errorAnalysisService;

    @Autowired
    private XRayService xRayService;

    @Autowired
    private KnowledgeAnalyzerService knowledgeAnalyzerService;

    @Autowired
    private FlowAnalyzerService flowAnalyzerService;


    public ApiExecutionResponse execute(ApiRequest apiRequest){

        if(apiRequest.isDiagnosticMode()){
            List<ApiExecutionResponse> attempts = new ArrayList<>();

            for(int i=0;i<3;i++){
                attempts.add(executeSingle(apiRequest));
            }

            return analyzeDiagnostic(attempts, apiRequest.getUrl());
        }

        ApiExecutionResponse response = executeSingle(apiRequest);

        KnowledgeAnalysis knowledge =
                knowledgeAnalyzerService.analysis(apiRequest.getUrl());

        response.setKnowledgeAnalysis(knowledge);

        return response;
    }


    private ApiExecutionResponse executeSingle(ApiRequest apiRequest){

        long start = System.currentTimeMillis();

        ApiResponse apiResponse;
        AtomicLong responseReceived = new AtomicLong();
        AtomicLong bodyParsed = new AtomicLong();

        XRayAnalysis xray;

        try {

            WebClient.RequestBodySpec requestSpec = webClient
                    .method(HttpMethod.valueOf(apiRequest.getMethod()))
                    .uri(apiRequest.getUrl())
                    .headers(headers -> {

                        headers.set("User-Agent", "APIX");

                        Map<String, String> reqHeaders = apiRequest.getHeaders();

                        if(reqHeaders != null){
                            reqHeaders.forEach((key,value)->{
                                String cleanKey = key.trim().replace(":","");
                                headers.set(cleanKey,value);
                            });
                        }
                    });

            WebClient.ResponseSpec responseSpec;

            if(apiRequest.getBody() != null &&
                    !apiRequest.getBody().isEmpty() &&
                    !apiRequest.getMethod().equalsIgnoreCase("GET")){

                responseSpec = requestSpec
                        .bodyValue(apiRequest.getBody())
                        .retrieve();

            } else {

                responseSpec = requestSpec.retrieve();
            }




            apiResponse = responseSpec
                    .toEntity(String.class)
                    .map(entity -> {


                        ApiResponse response = new ApiResponse();

                        responseReceived.set(System.currentTimeMillis());

                        response.setStatusCode(entity.getStatusCode().value());
                        response.setBody(entity.getBody());

                        Map<String,String> headersMap = new HashMap<>();

                        entity.getHeaders()
                                .forEach((key,value)-> headersMap.put(key,value.get(0)));

                        response.setHeaders(headersMap);

                        bodyParsed.set(System.currentTimeMillis());

                        return response;

                    })
                    .block();

            xray = xRayService.analysis(apiResponse.getStatusCode());

        }
        catch(Exception e){

            apiResponse = new ApiResponse();
            int status=500;
            if(e instanceof  org.springframework.web.reactive.function.client.WebClientResponseException ex){
                status=ex.getStatusCode().value();
            }
            apiResponse.setStatusCode(status);
            apiResponse.setBody("APIX Error: "+e.getMessage());

            xray = xRayService.analyzeException(e);
        }

        long end = System.currentTimeMillis();

        apiResponse.setResponseTime(end-start);

        ErrorAnalysis analysis =
                errorAnalysisService.analysis(apiResponse.getStatusCode());

        TimingTrace trace = new TimingTrace();
        trace.setRequestStart(start);
        trace.setResponseReceived(responseReceived.get());
        trace.setBodyParsed(bodyParsed.get());
        trace.setTotalTime(end-start);

        trace.setServerProcessingTime(responseReceived.get() -start);
        trace.setDownloadTime(bodyParsed.get() - responseReceived.get());

        ApiExecutionResponse response = new ApiExecutionResponse();

        Span currentSpan=Span.current();
        String traceId=currentSpan.getSpanContext().getTraceId();
        response.setTraceId(traceId);

        System.out.println("Trace id: " + traceId);

        FlowTrace flowTrace = flowAnalyzerService.analyze(trace);

        response.setApiResponse(apiResponse);
        response.setErrorAnalysis(analysis);
        response.setXRayAnalysis(xray);
        response.setTimingTrace(trace);
        response.setFlowTrace(flowTrace);

        FlowGraph graph = flowGraphBuilderService.build(flowTrace);
        response.setFlowGraph(graph);
        List<RequestHistory> hisory=historyService.getAllHistory();
        DependencyGraph deps=dependencyGraphBuilderService.build(hisory);
        response.setDependencyGraph(deps);

        String headersJson="{}";
        try {
            if(apiRequest.getHeaders()!=null){
                headersJson=objectMapper.writeValueAsString(apiRequest.getHeaders());
            }
        }catch (Exception ignored){}

        historyService.savedHistory(
                apiRequest.getMethod(),
                apiRequest.getUrl(),
                apiResponse.getStatusCode(),
                apiResponse.getResponseTime(),
                headersJson,
                apiRequest.getBody()
        );

        List<RequestHistory> history =
                historyService.getAllHistory(apiRequest.getUrl());

        TraceTimeline timeline =
                traceTimelineService.build(history);

        response.setTraceTimeline(timeline);

        return response;
    }


    private ApiExecutionResponse analyzeDiagnostic(List<ApiExecutionResponse> attempts, String url){

        int success = 0;

        for(ApiExecutionResponse r : attempts){
            if(r.getApiResponse().getStatusCode() < 400){
                success++;
            }
        }

        ApiExecutionResponse finalResult = attempts.get(0);

        if(success == 0){

            finalResult.getXRayAnalysis()
                    .setSuggestion("Persistent failure across retries. Backend likely unstable.");

        }
        else if(success < attempts.size()){

            finalResult.getXRayAnalysis()
                    .setSuggestion("Intermittent API instability detected.");

        }
        else{

            finalResult.getXRayAnalysis()
                    .setSuggestion("API recovered after retry.");
        }

        KnowledgeAnalysis knowledge =
                knowledgeAnalyzerService.analysis(url);

        finalResult.setKnowledgeAnalysis(knowledge);

        return finalResult;
    }
}