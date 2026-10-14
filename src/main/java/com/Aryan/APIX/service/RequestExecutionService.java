package com.Aryan.APIX.service;

import com.Aryan.APIX.ai.dto.AIRequest;
import com.Aryan.APIX.ai.dto.AIResponse;
import com.Aryan.APIX.ai.service.AIAnalysisService;
import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.model.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

@Service
public class RequestExecutionService {

    @Autowired
    private WebClient webClient;

    @Autowired
    private AIAnalysisService aiAnalysisService;

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
    private SensitiveDataRedactor redactor;

    @Autowired
    private OutboundDestinationPolicy destinationPolicy;

    @Autowired
    private LocalTestAgentService localTestAgentService;

    @Value("${apix.outbound.max-body-bytes:1048576}")
    private int maxOutboundBodyBytes;

    @Value("${apix.outbound.response-timeout-ms:30000}")
    private long maxOutboundResponseTimeoutMs;

    @Value("${apix.otel.ui-base-url:https://all-in-one-production-a9d1.up.railway.app/trace/}")
    private String traceUiBaseUrl = "https://all-in-one-production-a9d1.up.railway.app/trace/";

    private static final Tracer tracer =
            io.opentelemetry.api.GlobalOpenTelemetry.getTracer("APIX-Tracer");

    public ApiExecutionResponse execute(ApiRequest apiRequest){

        return execute(apiRequest, null);
    }

    public ApiExecutionResponse execute(ApiRequest apiRequest, Long ownerId){

        if (apiRequest == null || apiRequest.getUrl() == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "A valid HTTP or HTTPS target URL is required");
        }
        if (apiRequest.getAgentId() != null) return localTestAgentService.execute(apiRequest.getAgentId(), apiRequest, ownerId);
        destinationPolicy.validateUrl(apiRequest.getUrl());
        if (apiRequest.getBody() != null && (apiRequest.getBody().length() > maxOutboundBodyBytes
                || apiRequest.getBody().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxOutboundBodyBytes))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "Outbound request body exceeds the 1 MB limit");
        if (apiRequest.getHeaders() != null && (apiRequest.getHeaders().size() > 100
                || apiRequest.getHeaders().entrySet().stream().anyMatch(header -> header.getKey() == null || header.getValue() == null
                || header.getKey().length() > 200 || header.getValue().length() > 8192
                || header.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Outbound request headers exceed APiX limits");

        if (apiRequest.getTimeoutMs() != null && (apiRequest.getTimeoutMs() < 100 || apiRequest.getTimeoutMs() > 30_000)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "timeoutMs must be between 100 and 30000");
        }

        if(apiRequest.isDiagnosticMode()){
            List<ApiExecutionResponse> attempts = new ArrayList<>();

            for(int i=0;i<3;i++){
                attempts.add(executeSingle(apiRequest));
            }

            return analyzeDiagnostic(attempts, apiRequest.getUrl());
        }

        ApiExecutionResponse response = executeSingle(apiRequest);

        if (!apiRequest.isSuppressEnrichment()) {
            KnowledgeAnalysis knowledge = knowledgeAnalyzerService.analysis(apiRequest.getUrl());
            response.setKnowledgeAnalysis(knowledge);
        }

        return response;
    }

    private ApiExecutionResponse executeSingle(ApiRequest apiRequest){

        String executionId = UUID.randomUUID().toString();

        long start = System.nanoTime();

        ApiResponse apiResponse;
        AtomicLong responseReceived = new AtomicLong();
        AtomicLong bodyParsed = new AtomicLong();
        AIResponse aiAnalysis=null;
        boolean transportFailure = false;

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

                var responseMono = responseSpec
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

                        });
                long executionTimeoutMs = apiRequest.getTimeoutMs() == null ? maxOutboundResponseTimeoutMs : apiRequest.getTimeoutMs();
                apiResponse = responseMono.block(java.time.Duration.ofMillis(executionTimeoutMs));

                execEnd = System.nanoTime();

                executionSpan.setAttribute("http.status_code", apiResponse.getStatusCode());
                executionSpan.end();

                xray = xRayService.analysis(apiResponse.getStatusCode());

                if(apiResponse.getStatusCode()>=400 && !apiRequest.isSuppressAiAnalysis()){
                    AIRequest aiRequest=new AIRequest();

                    aiRequest.setRequestUrl(apiRequest.getUrl());
                    aiRequest.setMethod(apiRequest.getMethod());
                    aiRequest.setStatusCode(apiResponse.getStatusCode());
                    aiRequest.setResponseBody(apiResponse.getBody());
                    aiRequest.setTrace(xray.getSuggestion());
                    aiRequest.setLatency((long)((System.nanoTime() - start) / 1000000));

                    aiAnalysis=aiAnalysisService.analyzeFailure(aiRequest, apiRequest.getSensitiveValues());
                }

            }
            catch(Exception e){
                if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Outbound request cancelled", e);
                }

                apiResponse = new ApiResponse();

                int status = 500;
                if (hasPolicyBlock(e)) {
                    transportFailure = true;
                    status = 403;
                    apiResponse.setBody("Request blocked by APiX outbound network policy");
                    apiResponse.setHeaders(Map.of());
                } else if(e instanceof org.springframework.web.reactive.function.client.WebClientResponseException ex){
                    status = ex.getStatusCode().value();
                    apiResponse.setBody(ex.getResponseBodyAsString());
                    Map<String, String> errorHeaders = new HashMap<>();
                    ex.getHeaders().forEach((key, value) -> { if (!value.isEmpty()) errorHeaders.put(key, value.get(0)); });
                    apiResponse.setHeaders(errorHeaders);
                } else {
                    transportFailure = true;
                    apiResponse.setBody("APIX Error: outbound request failed; check the target and network policy");
                }

                apiResponse.setStatusCode(status);

                // Exception messages from HTTP clients can include URLs, headers, or response
                // fragments. Keep trace data useful without exporting the raw message.
                parentSpan.recordException(new RuntimeException("Outbound API request failed"));
                parentSpan.setStatus(StatusCode.ERROR);

                xray = xRayService.analyzeException(new RuntimeException("Outbound API request failed"));
                AIRequest aiRequest = new AIRequest();

                aiRequest.setRequestUrl(apiRequest.getUrl());
                aiRequest.setMethod(apiRequest.getMethod());
                aiRequest.setStatusCode(status);
                aiRequest.setResponseBody(apiResponse.getBody());
                aiRequest.setTrace("Outbound API request failed");
                aiRequest.setLatency((long)((System.nanoTime() - start) / 1000000));

                if(!apiRequest.isSuppressAiAnalysis()) {
                    aiAnalysis = aiAnalysisService.analyzeFailure(aiRequest, apiRequest.getSensitiveValues());
                }
            }

        } finally {
            parentSpan.end();
        }

        long end = System.nanoTime();


        long totalTimeMs = (end - start) / 1000000;
        apiResponse.setResponseTime(totalTimeMs);

        long connectionTime = connEnd > connStart ? (connEnd - connStart) / 1000000 : 0;
        long executionTime = responseReceived.get() > execStart ? (responseReceived.get() - execStart) / 1000000 : 0;
        long responseTime = bodyParsed.get() > responseReceived.get() && responseReceived.get() > 0
                ? (bodyParsed.get() - responseReceived.get()) / 1000000 : 0;


        ErrorAnalysis analysis =
                errorAnalysisService.analysis(apiResponse.getStatusCode());


        TimingTrace trace = new TimingTrace();
        trace.setRequestStart(System.currentTimeMillis() - totalTimeMs);
        trace.setResponseReceived(responseReceived.get() == 0 ? null : responseReceived.get() / 1000000);
        trace.setBodyParsed(bodyParsed.get() == 0 ? null : bodyParsed.get() / 1000000);
        trace.setTotalTime(totalTimeMs);

        trace.setServerProcessingTime(responseReceived.get() == 0 ? null : Math.max(0, (responseReceived.get() - start) / 1000000));
        trace.setDownloadTime(bodyParsed.get() == 0 || responseReceived.get() == 0 ? null : responseTime);
        trace.setConnectionTime(connectionTime);
        trace.setExecutionTime(executionTime);

        ApiExecutionResponse response = new ApiExecutionResponse();

        String traceId = parentSpan.getSpanContext().getTraceId();
        response.setTraceUrl(traceUiBaseUrl.replaceAll("/+$", "") + "/" + traceId);
        response.setTraceId(traceId);
        response.setExecutionId(executionId);
        response.setExecutionError(transportFailure);

        FlowTrace flowTrace = flowAnalyzerService.analyze(trace);

        response.setApiResponse(apiResponse);
        response.setErrorAnalysis(analysis);
        response.setXRayAnalysis(xray);
        response.setTimingTrace(trace);
        response.setFlowTrace(flowTrace);


        Map<String,Long> timings = new HashMap<>();
        if(connEnd > connStart) timings.put("connection", connectionTime);
        if(responseReceived.get() > execStart) timings.put("execution", executionTime);
        if(bodyParsed.get() > responseReceived.get() && responseReceived.get() > 0) timings.put("response", responseTime);

        apiResponse.setTimings(timings);

        FlowGraph graph = flowGraphBuilderService.build(flowTrace);
        response.setFlowGraph(graph);

        if (!apiRequest.isSuppressEnrichment()) {
            List<RequestHistory> history = historyService.getAllHistory();
            DependencyGraph deps = dependencyGraphBuilderService.build(history);
            response.setDependencyGraph(deps);
        }

        String headersJson="{}";
        try {
            if(apiRequest.getHeaders()!=null){
                headersJson=objectMapper.writeValueAsString(redactor.redactHeaders(apiRequest.getHeaders(), apiRequest.getSensitiveValues()));
            }
        }catch (Exception ignored){}

        historyService.savedHistory(
                apiRequest.getMethod(),
                redactor.redactText(redactor.redactUrl(apiRequest.getUrl()), apiRequest.getSensitiveValues()),
                apiResponse.getStatusCode(),
                apiResponse.getResponseTime(),
                headersJson,
                redactor.redactText(apiRequest.getBody(), apiRequest.getSensitiveValues()),
                executionId,
                traceId
        );

        if (!apiRequest.isSuppressEnrichment()) {
            List<RequestHistory> historyByUrl = historyService.getAllHistory(apiRequest.getUrl());
            TraceTimeline timeline = traceTimelineService.build(historyByUrl);
            response.setTraceTimeline(timeline);
        }

        response.setAiAnalysis(aiAnalysis);
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

    private static boolean hasPolicyBlock(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause())
            if (current instanceof OutboundPolicyBlockedException) return true;
        return false;
    }
}
