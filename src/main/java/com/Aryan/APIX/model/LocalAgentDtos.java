package com.Aryan.APIX.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class LocalAgentDtos {
    private LocalAgentDtos() { }
    public record TargetGrant(String host, int port) { }
    public record PairingCreateRequest(String name, List<TargetGrant> allowedTargets) { }
    public record PairingCreated(String pairingCode, LocalDateTime expiresAt) { }
    public record PairRequest(String pairingCode) { }
    public record PairResponse(Long agentId, String credential, List<TargetGrant> allowedTargets) { }
    public record AgentView(Long id, String name, String status, List<TargetGrant> allowedTargets,
                            LocalDateTime createdAt, LocalDateTime lastSeenAt) { }
    public record AgentJob(String id, String method, String url, Map<String, String> headers, String body,
                           long timeoutMs, int maxResponseBytes) { }
    public record AgentJobResult(int statusCode, Map<String, String> headers, String body, long elapsedMs, String error) { }
}
