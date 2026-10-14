package com.Aryan.APIX.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class SuiteDtos {
    private SuiteDtos() { }

    public record SuiteUpsert(String name, String description, List<SuiteCase> cases, String failurePolicy) { }
    public record SuiteCase(String id, String name, ApiRequest request, List<AssertionSpec> assertions,
                            Map<String, ExtractionSpec> extract, List<String> dependsOn, Long timeoutMs,
                            String protocol, WebSocketScenario webSocket) {
        public SuiteCase(String id, String name, ApiRequest request, List<AssertionSpec> assertions,
                         Map<String, ExtractionSpec> extract, List<String> dependsOn, Long timeoutMs) {
            this(id, name, request, assertions, extract, dependsOn, timeoutMs, "HTTP", null);
        }
    }
    public record WebSocketScenario(String url, Map<String, String> headers, Long connectTimeoutMs,
                                    List<WebSocketStep> steps) { }
    public record WebSocketStep(String action, String message, Long timeoutMs,
                                List<AssertionSpec> assertions) { }
    public record AssertionSpec(String type, String path, Object expected) { }
    public record ExtractionSpec(String jsonPath, boolean secret) { }
    public record RunRequest(Map<String, String> variables, List<String> secretVariables) { }
    public record CaseOrder(List<String> caseIds) { }

    public record SuiteView(Long id, String name, String description, List<SuiteCase> cases,
                            String failurePolicy, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record PageEnvelope<T>(List<T> content, int page, int size, long totalElements, int totalPages) { }
    public record AssertionResult(String type, String path, boolean passed, Object expected,
                                  Object actual, String message) { }
    public record WebSocketMessageResult(int stepIndex, String payload, List<AssertionResult> assertions) { }
    public record CaseResult(String caseId, String name, String status, List<AssertionResult> assertions,
                             Integer statusCode, Long responseTimeMs, String executionId, String traceId,
                             Map<String, String> responseHeaders, String responseBody, String error,
                             LocalDateTime startedAt, LocalDateTime completedAt, String protocol,
                             List<WebSocketMessageResult> webSocketMessages) {
        public CaseResult(String caseId, String name, String status, List<AssertionResult> assertions,
                          Integer statusCode, Long responseTimeMs, String executionId, String traceId,
                          Map<String, String> responseHeaders, String responseBody, String error) {
            this(caseId, name, status, assertions, statusCode, responseTimeMs, executionId, traceId,
                    responseHeaders, responseBody, error, null, null, "HTTP", List.of());
        }
        public CaseResult(String caseId, String name, String status, List<AssertionResult> assertions,
                          Integer statusCode, Long responseTimeMs, String executionId, String traceId,
                          Map<String, String> responseHeaders, String responseBody, String error,
                          LocalDateTime startedAt, LocalDateTime completedAt) {
            this(caseId, name, status, assertions, statusCode, responseTimeMs, executionId, traceId,
                    responseHeaders, responseBody, error, startedAt, completedAt, "HTTP", List.of());
        }
    }
    public record RunView(Long id, Long suiteId, String suiteName, String status, int passed, int failed,
                          int skipped, int errored, LocalDateTime startedAt, LocalDateTime completedAt,
                          Long durationMs, List<CaseResult> results, String failureSummary, int totalCases,
                          int completedCases, String currentCaseId, String currentCaseName) { }
}
