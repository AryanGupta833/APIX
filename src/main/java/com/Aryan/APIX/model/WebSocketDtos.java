package com.Aryan.APIX.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class WebSocketDtos {
    private WebSocketDtos() { }

    public record ConnectRequest(String url, Map<String, String> headers) { }
    public record SendMessageRequest(String message) { }
    public record MessageView(long sequence, LocalDateTime receivedAt, String type, String payload) { }
    public record SessionView(String id, String state, String target, int handshakeStatus,
                              Map<String, String> handshakeHeaders, LocalDateTime connectedAt,
                              LocalDateTime expiresAt, List<MessageView> messages, String error) { }
}
