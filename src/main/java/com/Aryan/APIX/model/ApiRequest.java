package com.Aryan.APIX.model;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ApiRequest {
    private String method;
    private String url;
    private Map<String, String> headers;
    private String body;
    private Map<String,String> queryParams;
    private Boolean diagnosticMode = false;
    // Optional internal controls used by suite execution; omitted by existing clients.
    private Long timeoutMs;
    private Boolean suppressAiAnalysis = false;
    private Boolean suppressEnrichment = false;
    private List<String> sensitiveValues;
    // Selects the separately authenticated local-agent transport; null keeps ordinary public outbound execution.
    private Long agentId;

    public boolean isDiagnosticMode() { return Boolean.TRUE.equals(diagnosticMode); }
    public void setDiagnosticMode(Boolean diagnosticMode) { this.diagnosticMode = Boolean.TRUE.equals(diagnosticMode); }
    public boolean isSuppressAiAnalysis() { return Boolean.TRUE.equals(suppressAiAnalysis); }
    public void setSuppressAiAnalysis(Boolean suppressAiAnalysis) { this.suppressAiAnalysis = Boolean.TRUE.equals(suppressAiAnalysis); }
    public boolean isSuppressEnrichment() { return Boolean.TRUE.equals(suppressEnrichment); }
    public void setSuppressEnrichment(Boolean suppressEnrichment) { this.suppressEnrichment = Boolean.TRUE.equals(suppressEnrichment); }


}
