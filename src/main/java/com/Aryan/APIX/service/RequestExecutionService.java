package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.model.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

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

    @Autowired
    private Tracer tracer;

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

        long start = System.nanoTime();

        ApiResponse apiResponse;
        AtomicLong responseReceived = new AtomicLong();
        AtomicLong bodyParsed = new AtomicLong();

        XRayAnalysis xray;

        long connStart=0,connEnd=0;
        long execStart=0,execEnd=0;

        Span parentSpan = tracer.spanBuilder("API Request").startSpan();

        try (Scope scope = parentSpan.makeCurrent()) {

            try {

                Span connectionSpan = tracer.spanBuilder("Connection Phase").startSpan();
                connStart = System.nanoTime();

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

                connEnd = System.nanoTime();
                connectionSpan.end();


                Span executionSpan = tracer.spanBuilder("Execution Phase").startSpan();
                execStart = System.nanoTime();

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


                            responseReceived.set(System.nanoTime());

                            response.setStatusCode(entity.getStatusCode().value());
                            response.setBody(entity.getBody());

                            Map<String,String> headersMap = new HashMap<>();

                            entity.getHeaders()
                                    .forEach((key,value)-> headersMap.put(key,value.get(0)));

                            response.setHeaders(headersMap);


                            bodyParsed.set(System.nanoTime());

                            return response;

                        })
                        .block();

                execEnd = System.nanoTime();

                executionSpan.setAttribute("http.status_code", apiResponse.getStatusCode());
                executionSpan.end();

                xray = xRayService.analysis(apiResponse.getStatusCode());

            }
            catch(Exception e){

                apiResponse = new ApiResponse();

                int status = 500;
                if(e instanceof org.springframework.web.reactive.function.client.WebClientResponseException ex){
                    status = ex.getStatusCode().value();
                }

                apiResponse.setStatusCode(status);
                apiResponse.setBody("APIX Error: " + e.getMessage());

                parentSpan.recordException(e);
                parentSpan.setStatus(StatusCode.ERROR);

                xray = xRayService.analyzeException(e);
            }

        } finally {
            parentSpan.end();
        }

        long end = System.nanoTime();


        long totalTimeMs = (end - start) / 1000000;
        apiResponse.setResponseTime(totalTimeMs);

        long connectionTime = (connEnd - connStart) / 1000000;
        long executionTime = (responseReceived.get() - execStart) / 1000000;
        long responseTime = (bodyParsed.get() - responseReceived.get()) / 1000000;


        if(connectionTime <= 0) connectionTime = 1;
        if(executionTime <= 0) executionTime = 1;
        if(responseTime <= 0) responseTime = 1;

        ErrorAnalysis analysis =
                errorAnalysisService.analysis(apiResponse.getStatusCode());


        TimingTrace trace = new TimingTrace();
        trace.setRequestStart(start / 1000000);
        trace.setResponseReceived(responseReceived.get() / 1000000);
        trace.setBodyParsed(bodyParsed.get() / 1000000);
        trace.setTotalTime(totalTimeMs);

        trace.setServerProcessingTime(
                (responseReceived.get() - start) / 1000000
        );
        trace.setDownloadTime(
                (bodyParsed.get() - responseReceived.get()) / 1000000
        );

        ApiExecutionResponse response = new ApiExecutionResponse();

        String traceId = parentSpan.getSpanContext().getTraceId();
        response.setTraceId(traceId);

        FlowTrace flowTrace = flowAnalyzerService.analyze(trace);

        response.setApiResponse(apiResponse);
        response.setErrorAnalysis(analysis);
        response.setXRayAnalysis(xray);
        response.setTimingTrace(trace);
        response.setFlowTrace(flowTrace);


        Map<String,Long> timings = new HashMap<>();
        timings.put("connection", connectionTime);
        timings.put("execution", executionTime);
        timings.put("response", responseTime);

        apiResponse.setTimings(timings);

        FlowGraph graph = flowGraphBuilderService.build(flowTrace);
        response.setFlowGraph(graph);

        List<RequestHistory> history = historyService.getAllHistory();
        DependencyGraph deps = dependencyGraphBuilderService.build(history);
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

        List<RequestHistory> historyByUrl =
                historyService.getAllHistory(apiRequest.getUrl());

        TraceTimeline timeline =
                traceTimelineService.build(historyByUrl);

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