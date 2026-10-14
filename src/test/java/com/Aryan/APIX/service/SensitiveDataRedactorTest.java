package com.Aryan.APIX.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataRedactorTest {
    private final SensitiveDataRedactor redactor = new SensitiveDataRedactor();

    @Test
    void redactsSensitiveHeadersAndLeavesOrdinaryHeaders() {
        Map<String, String> safe = redactor.redactHeaders(Map.of(
                "Authorization", "Bearer actual-token",
                "X-API-Key", "actual-key",
                "Accept", "application/json"));

        assertEquals("[REDACTED]", safe.get("Authorization"));
        assertEquals("[REDACTED]", safe.get("X-API-Key"));
        assertEquals("application/json", safe.get("Accept"));
    }

    @Test
    void redactsSensitiveJsonAndQueryParameters() {
        String body = redactor.redactText("{\"user\":\"alice\",\"password\":\"secret-value\"}");
        String url = redactor.redactUrl("https://example.test/items?api_key=secret-value&page=2");

        assertTrue(body.contains("[REDACTED]"));
        assertTrue(!body.contains("secret-value"));
        assertTrue(url.contains("api_key=[REDACTED]"));
        assertTrue(!url.contains("secret-value"));
        assertTrue(url.contains("page=2"));
    }

    @Test
    void redactsExplicitSecretValuesEvenWhenUrlEncoded() {
        String safe = redactor.redactText("https://example.test/users/a%2Fb?ref=a%2Fb", java.util.List.of("a/b"));
        assertFalse(safe.contains("a%2Fb"));
        assertTrue(safe.contains("[REDACTED]"));
    }
}
