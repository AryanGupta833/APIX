package com.Aryan.APIX.service;

import com.Aryan.APIX.model.ApiExecutionResponse;
import com.Aryan.APIX.model.ApiResponse;
import com.Aryan.APIX.model.SuiteDtos.AssertionResult;
import com.Aryan.APIX.model.SuiteDtos.AssertionSpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.github.fge.jsonschema.main.JsonSchemaFactory;

@Service
public class ApiAssertionService {
    private static final com.fasterxml.jackson.databind.ObjectMapper SCHEMA_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.byDefault();
    private final ObjectMapper mapper;

    public ApiAssertionService(ObjectMapper mapper) { this.mapper = mapper; }

    public List<AssertionResult> evaluate(List<AssertionSpec> assertions, ApiExecutionResponse execution) {
        List<AssertionResult> results = new ArrayList<>();
        ApiResponse response = execution == null ? null : execution.getApiResponse();
        JsonNode json = null;
        boolean jsonParsed = false;
        boolean invalidJson = false;
        if (response != null && response.getBody() != null) {
            try { json = mapper.readTree(response.getBody()); jsonParsed = true; }
            catch (Exception ignored) { invalidJson = true; }
        }
        for (AssertionSpec spec : assertions == null ? List.<AssertionSpec>of() : assertions) {
            String type = spec.type() == null ? "" : spec.type().trim().toUpperCase();
            Object actual = null;
            boolean passed = false;
            String message;
            try {
                switch (type) {
                    case "REQUEST_COMPLETED" -> {
                        actual = execution != null && !execution.isExecutionError();
                        passed = Boolean.TRUE.equals(actual);
                        message = passed ? "Request completed" : "Request did not complete successfully";
                    }
                    case "STATUS_EQUALS" -> {
                        actual = response == null ? null : response.getStatusCode();
                        passed = numericEquals(actual, spec.expected());
                        message = passed ? "Status code matched" : "Expected status " + spec.expected() + " but received " + actual;
                    }
                    case "STATUS_IN" -> {
                        actual = response == null ? null : response.getStatusCode();
                        if (spec.expected() instanceof List<?> options) {
                            for (Object option : options) if (numericEquals(actual, option)) { passed = true; break; }
                        }
                        message = passed ? "Status code is allowed" : "Received status is not in the allowed set";
                    }
                    case "HEADER_PRESENT", "HEADER_EQUALS" -> {
                        actual = response == null || response.getHeaders() == null ? null : header(response.getHeaders(), spec.path());
                        passed = type.equals("HEADER_PRESENT") ? actual != null : Objects.equals(Objects.toString(actual, null), Objects.toString(spec.expected(), null));
                        message = passed ? "Response header assertion passed" : "Response header assertion failed for " + spec.path();
                    }
                    case "RESPONSE_TIME_LTE" -> {
                        actual = response == null ? null : response.getResponseTime();
                        passed = actual instanceof Number n && spec.expected() instanceof Number limit && n.doubleValue() <= limit.doubleValue();
                        message = passed ? "Response time was within the limit" : "Response time exceeded the limit or was unavailable";
                    }
                    case "BODY_PRESENT", "BODY_ABSENT" -> {
                        actual = response != null && response.getBody() != null && !response.getBody().isEmpty();
                        passed = type.equals("BODY_PRESENT") == Boolean.TRUE.equals(actual);
                        message = passed ? "Response body presence matched" : "Response body presence did not match";
                    }
                    case "JSON_SCHEMA" -> {
                        if (response == null || response.getBody() == null) {
                            actual = null; passed = false; message = "Response body is unavailable for JSON Schema validation";
                        } else {
                            try {
                                com.fasterxml.jackson.databind.JsonNode schemaNode = SCHEMA_MAPPER.valueToTree(spec.expected());
                                if (hasRemoteReference(schemaNode)) throw new IllegalArgumentException("Remote JSON Schema references are not allowed");
                                var schema = SCHEMA_FACTORY.getJsonSchema(schemaNode);
                                var report = schema.validate(SCHEMA_MAPPER.readTree(response.getBody()));
                                actual = report.isSuccess() ? "valid" : report.toString();
                                passed = report.isSuccess();
                                message = passed ? "Response matches the JSON Schema" : "Response does not match the JSON Schema";
                            } catch (Exception e) {
                                actual = null; passed = false; message = "Invalid JSON Schema or response JSON: " + e.getMessage();
                            }
                        }
                    }
                    case "JSON_EXISTS", "JSON_NOT_EXISTS", "JSON_EQUALS", "JSON_NOT_EQUALS", "JSON_GT", "JSON_GTE", "JSON_LT", "JSON_LTE" -> {
                        if (!jsonParsed) {
                            actual = null;
                            passed = false;
                            message = invalidJson ? "Response body is not valid JSON" : "Response body is unavailable";
                        } else {
                            JsonLookup found = lookup(json, spec.path());
                            actual = found.exists() ? toValue(found.value()) : null;
                            passed = switch (type) {
                                case "JSON_EXISTS" -> found.exists();
                                case "JSON_NOT_EXISTS" -> !found.exists();
                                case "JSON_EQUALS" -> found.exists() && valuesEqual(actual, spec.expected());
                                case "JSON_NOT_EQUALS" -> !found.exists() || !valuesEqual(actual, spec.expected());
                                case "JSON_GT" -> numeric(actual, spec.expected(), ">");
                                case "JSON_GTE" -> numeric(actual, spec.expected(), ">=");
                                case "JSON_LT" -> numeric(actual, spec.expected(), "<");
                                case "JSON_LTE" -> numeric(actual, spec.expected(), "<=");
                                default -> false;
                            };
                            message = passed ? "JSON assertion passed" : "JSON assertion failed at " + spec.path();
                        }
                    }
                    default -> message = "Unsupported assertion type: " + type;
                }
            } catch (RuntimeException e) {
                passed = false;
                message = "Invalid assertion configuration: " + e.getMessage();
            }
            results.add(new AssertionResult(type, spec.path(), passed, spec.expected(), actual, message));
        }
        return results;
    }

    public List<AssertionResult> evaluateWebSocketMessage(String payload, List<AssertionSpec> assertions) {
        List<AssertionResult> results = new ArrayList<>();
        for (AssertionSpec spec : assertions == null ? List.<AssertionSpec>of() : assertions) {
            String type = spec.type() == null ? "" : spec.type().trim().toUpperCase(java.util.Locale.ROOT);
            if (type.equals("MESSAGE_EQUALS") || type.equals("MESSAGE_CONTAINS")) {
                boolean passed = payload != null && spec.expected() instanceof String expected
                        && (type.equals("MESSAGE_EQUALS") ? payload.equals(expected) : payload.contains(expected));
                results.add(new AssertionResult(type, null, passed, spec.expected(), payload,
                        passed ? "WebSocket text assertion passed" : "WebSocket text assertion failed"));
            } else {
                ApiResponse response = new ApiResponse();
                response.setStatusCode(101);
                response.setBody(payload);
                response.setHeaders(Map.of());
                ApiExecutionResponse execution = new ApiExecutionResponse();
                execution.setApiResponse(response);
                execution.setExecutionError(false);
                List<AssertionResult> evaluated = evaluate(List.of(spec), execution);
                results.addAll(evaluated);
            }
        }
        return results;
    }

    public Object extractJson(String body, String expression) {
        try { return toValue(lookup(mapper.readTree(body), expression).value()); }
        catch (Exception ignored) { return null; }
    }

    private static String header(Map<String, String> headers, String name) {
        if (name == null) return null;
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static boolean numericEquals(Object left, Object right) {
        if (!(left instanceof Number) || !(right instanceof Number)) return false;
        return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
    }

    private static boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) return numericEquals(left, right);
        if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
            if (leftList.size() != rightList.size()) return false;
            for (int i = 0; i < leftList.size(); i++) if (!valuesEqual(leftList.get(i), rightList.get(i))) return false;
            return true;
        }
        if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
            if (!leftMap.keySet().equals(rightMap.keySet())) return false;
            for (Object key : leftMap.keySet()) if (!valuesEqual(leftMap.get(key), rightMap.get(key))) return false;
            return true;
        }
        return Objects.deepEquals(left, right);
    }

    private static boolean numeric(Object left, Object right, String operation) {
        if (!(left instanceof Number) || !(right instanceof Number)) return false;
        int cmp = new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
        return switch (operation) { case ">" -> cmp > 0; case ">=" -> cmp >= 0; case "<" -> cmp < 0; default -> cmp <= 0; };
    }

    private static Object toValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return node.decimalValue();
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(toValue(item)));
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            node.properties().forEach(entry -> values.put(entry.getKey(), toValue(entry.getValue())));
            return values;
        }
        return node.toString();
    }

    private static JsonLookup lookup(JsonNode root, String path) {
        if (path == null || !path.startsWith("$")) throw new IllegalArgumentException("JSONPath must start with $");
        JsonNode current = root;
        int i = 1;
        while (i < path.length()) {
            if (path.charAt(i) == '.') {
                int start = ++i;
                while (i < path.length() && path.charAt(i) != '.' && path.charAt(i) != '[') i++;
                if (start == i) throw new IllegalArgumentException("Empty JSONPath field");
                current = current == null ? null : current.get(path.substring(start, i));
            } else if (path.charAt(i) == '[') {
                int end = path.indexOf(']', i + 1);
                if (end < 0) throw new IllegalArgumentException("Unclosed JSONPath bracket");
                String part = path.substring(i + 1, end);
                if (part.matches("\\d+")) current = current == null ? null : current.get(Integer.parseInt(part));
                else if (part.length() >= 2 && (part.charAt(0) == '\'' || part.charAt(0) == '"') && part.charAt(part.length() - 1) == part.charAt(0))
                    current = current == null ? null : current.get(part.substring(1, part.length() - 1));
                else throw new IllegalArgumentException("Only numeric indexes and quoted property names are supported");
                i = end + 1;
            } else throw new IllegalArgumentException("Invalid JSONPath near position " + i);
            if (current != null && current.isMissingNode()) current = null;
        }
        return new JsonLookup(current != null, current);
    }

    private static boolean hasRemoteReference(com.fasterxml.jackson.databind.JsonNode schema) {
        if (schema == null) return false;
        if (schema.isObject()) {
            var reference = schema.get("$ref");
            if (reference != null && reference.isTextual() && !reference.textValue().startsWith("#")) return true;
            var dialect = schema.get("$schema");
            if (dialect != null && dialect.isTextual() && !dialect.textValue().startsWith("#")) return true;
            var fields = schema.fields();
            while (fields.hasNext()) if (hasRemoteReference(fields.next().getValue())) return true;
        } else if (schema.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : schema) if (hasRemoteReference(child)) return true;
        }
        return false;
    }

    private record JsonLookup(boolean exists, JsonNode value) { }
}
