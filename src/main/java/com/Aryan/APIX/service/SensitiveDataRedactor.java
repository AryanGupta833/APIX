package com.Aryan.APIX.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redacts common credential fields before evidence leaves the execution path. */
@Component
public class SensitiveDataRedactor {
    private static final String SECRET_KEY = "(?i)(?:authorization|proxy-authorization|cookie|set-cookie|api[-_]?key|access[-_]?token|refresh[-_]?token|token|secret|password|passwd|credential)";
    private static final Pattern JSON_FIELD = Pattern.compile("(?i)(\\\"(?:" + SECRET_KEY + ")\\\"\\s*:\\s*\\\")(.*?)(\\\")");
    private static final Pattern QUERY_SECRET = Pattern.compile("(?i)([?&](?:" + SECRET_KEY + ")=)[^&#]*");

    public Map<String, String> redactHeaders(Map<String, String> headers) {
        if (headers == null) return null;
        Map<String, String> safe = new LinkedHashMap<>();
        headers.forEach((key, value) -> safe.put(key, isSecret(key) ? "[REDACTED]" : value));
        return safe;
    }

    public String redactUrl(String url) {
        return url == null ? null : QUERY_SECRET.matcher(url).replaceAll("$1[REDACTED]");
    }

    public String redactText(String value) {
        if (value == null) return null;
        Matcher matcher = JSON_FIELD.matcher(value);
        String safe = matcher.replaceAll("$1[REDACTED]$3");
        return QUERY_SECRET.matcher(safe).replaceAll("$1[REDACTED]");
    }

    public String redactText(String value, Collection<String> additionalSecrets) {
        String safe = redactText(value);
        if (safe == null || additionalSecrets == null) return safe;
        for (String secret : additionalSecrets) {
            if (secret != null && !secret.isEmpty()) {
                safe = safe.replace(secret, "[REDACTED]");
                String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
                if (!encoded.equals(secret)) safe = safe.replace(encoded, "[REDACTED]");
            }
        }
        return safe;
    }

    public Map<String, String> redactHeaders(Map<String, String> headers, Collection<String> additionalSecrets) {
        Map<String, String> safe = redactHeaders(headers);
        if (safe == null || additionalSecrets == null) return safe;
        safe.replaceAll((key, value) -> redactText(value, additionalSecrets));
        return safe;
    }

    private boolean isSecret(String key) {
        return key != null && key.matches("(?i).*" + SECRET_KEY + ".*");
    }
}
