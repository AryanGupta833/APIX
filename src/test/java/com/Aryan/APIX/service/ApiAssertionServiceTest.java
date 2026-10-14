package com.Aryan.APIX.service;

import com.Aryan.APIX.model.ApiExecutionResponse;
import com.Aryan.APIX.model.ApiResponse;
import com.Aryan.APIX.model.SuiteDtos.AssertionResult;
import com.Aryan.APIX.model.SuiteDtos.AssertionSpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ApiAssertionServiceTest {
    private final ApiAssertionService service = new ApiAssertionService(JsonMapper.builder().build());

    @Test
    void evaluatesHttpAndJsonAssertionsIncludingNumericComparisons() {
        ApiExecutionResponse execution = response(201, "{\"user\":{\"id\":7,\"active\":true},\"items\":[{\"name\":\"book\"}]}");
        execution.getApiResponse().setHeaders(Map.of("Content-Type", "application/json", "X-Request-Id", "req-1"));
        execution.getApiResponse().setResponseTime(45);
        List<AssertionResult> results = service.evaluate(List.of(
                new AssertionSpec("REQUEST_COMPLETED", null, null),
                new AssertionSpec("STATUS_IN", null, List.of(200, 201)),
                new AssertionSpec("HEADER_PRESENT", "x-request-id", null),
                new AssertionSpec("RESPONSE_TIME_LTE", null, 50),
                new AssertionSpec("JSON_EXISTS", "$.user.id", null),
                new AssertionSpec("JSON_EQUALS", "$.items[0].name", "book"),
                new AssertionSpec("JSON_EQUALS", "$.user", Map.of("id", 7, "active", true)),
                new AssertionSpec("JSON_GT", "$.user.id", 6)), execution);

        assertEquals(8, results.size());
        assertTrue(results.stream().allMatch(AssertionResult::passed));
        assertEquals(new java.math.BigDecimal("7"), service.extractJson(execution.getApiResponse().getBody(), "$.user.id"));
    }

    @Test
    void missingMalformedAndUnsupportedAssertionsFailSafely() {
        ApiExecutionResponse execution = response(200, "{broken");
        List<AssertionResult> malformed = service.evaluate(List.of(new AssertionSpec("JSON_EXISTS", "$.id", null)), execution);
        List<AssertionResult> unsupported = service.evaluate(List.of(new AssertionSpec("CUSTOM_CODE", "$", Map.of())), response(200, "{}"));
        List<AssertionResult> absent = service.evaluate(List.of(new AssertionSpec("BODY_ABSENT", null, null)), response(200, null));
        List<AssertionResult> missing = service.evaluate(List.of(new AssertionSpec("JSON_EXISTS", "$.missing", null)), response(200, "{}"));

        assertFalse(malformed.getFirst().passed());
        assertTrue(malformed.getFirst().message().contains("valid JSON"));
        assertFalse(unsupported.getFirst().passed());
        assertTrue(unsupported.getFirst().message().contains("Unsupported"));
        assertTrue(absent.getFirst().passed());
        assertFalse(missing.getFirst().passed());
    }

    @Test
    void transportFailuresDoNotMasqueradeAsHttpStatusFailures() {
        ApiExecutionResponse execution = response(500, "APIX Error: timeout"); execution.setExecutionError(true);
        List<AssertionResult> results = service.evaluate(List.of(
                new AssertionSpec("REQUEST_COMPLETED", null, null),
                new AssertionSpec("STATUS_EQUALS", null, 500)), execution);
        assertFalse(results.get(0).passed());
        assertTrue(results.get(1).passed());
    }

    @Test
    void validatesJsonSchemaWithoutFollowingRemoteReferences() {
        var validSchema = Map.of("type", "object", "required", List.of("id"), "properties", Map.of("id", Map.of("type", "integer")));
        var remoteSchema = Map.of("$ref", "https://schemas.example.test/user.json");
        List<AssertionResult> valid = service.evaluate(List.of(new AssertionSpec("JSON_SCHEMA", null, validSchema)), response(200, "{\"id\":3}"));
        List<AssertionResult> rejected = service.evaluate(List.of(new AssertionSpec("JSON_SCHEMA", null, remoteSchema)), response(200, "{}"));
        assertTrue(valid.getFirst().passed());
        assertFalse(rejected.getFirst().passed());
        assertTrue(rejected.getFirst().message().contains("Remote JSON Schema references are not allowed"));
    }

    @Test
    void localSchemaReferencesResolveWithinTheSuppliedSchema() {
        var schema = Map.of("type", "object", "properties", Map.of("user", Map.of("$ref", "#/definitions/user")),
                "required", List.of("user"), "definitions", Map.of("user", Map.of("type", "object", "required", List.of("id"),
                        "properties", Map.of("id", Map.of("type", "integer")))));
        var valid = service.evaluate(List.of(new AssertionSpec("JSON_SCHEMA", null, schema)), response(200, "{\"user\":{\"id\":5}}"));
        var invalid = service.evaluate(List.of(new AssertionSpec("JSON_SCHEMA", null, schema)), response(200, "{\"user\":{}}"));
        assertTrue(valid.getFirst().passed());
        assertFalse(invalid.getFirst().passed());
    }

    private ApiExecutionResponse response(int status, String body) {
        ApiResponse response = new ApiResponse(); response.setStatusCode(status); response.setBody(body); response.setHeaders(Map.of()); response.setResponseTime(10);
        ApiExecutionResponse execution = new ApiExecutionResponse(); execution.setApiResponse(response); return execution;
    }
}
