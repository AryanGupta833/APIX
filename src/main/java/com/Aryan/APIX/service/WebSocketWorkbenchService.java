package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.User;
import com.Aryan.APIX.model.WebSocketDtos.ConnectRequest;
import com.Aryan.APIX.model.WebSocketDtos.MessageView;
import com.Aryan.APIX.model.WebSocketDtos.SessionView;
import com.Aryan.APIX.repository.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Service
public class WebSocketWorkbenchService {
    private static final int MAX_HEADER_COUNT = 50;
    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_FRAME_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_MESSAGES = 500;
    private static final int MAX_RETAINED_MESSAGES = 200;
    private static final int MAX_ACTIVE_PER_USER = 3;
    private static final int MAX_ACTIVE_GLOBAL = 20;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SESSION_DURATION = Duration.ofMinutes(10);
    private static final Pattern HEADER_TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern SECRET_HEADER = Pattern.compile("(?i).*(authorization|cookie|token|secret|password|credential|api[-_]?key).*");

    private final WebSocketClient client;
    private final OutboundDestinationPolicy destinationPolicy;
    private final CurrentUserService users;
    private final SensitiveDataRedactor redactor;
    private final ConcurrentHashMap<String, ManagedSession> sessions = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();

    public WebSocketWorkbenchService(WebSocketClient client, OutboundDestinationPolicy destinationPolicy,
                                     CurrentUserService users, SensitiveDataRedactor redactor) {
        this.client = client;
        this.destinationPolicy = destinationPolicy;
        this.users = users;
        this.redactor = redactor;
    }

    public SessionView connect(ConnectRequest request) {
        User user = users.requireUser();
        return connectForOwner(user.getId(), request, List.of());
    }

    /** Internal suite entry point. Ownership is explicit because suite workers do not run on the HTTP request thread. */
    SessionView connectForOwner(Long ownerId, ConnectRequest request, List<String> additionalSecrets) {
        if (ownerId == null) throw badRequest("A session owner is required");
        if (request == null || request.url() == null || request.url().length() > 4000)
            throw badRequest("A valid WebSocket URL is required");
        URI uri = destinationPolicy.validateWebSocketUrl(request.url());
        HttpHeaders headers = validateHeaders(request.headers());
        evictOldSessions();
        List<String> secrets = new ArrayList<>(requestSecrets(request.headers()));
        if (additionalSecrets != null) additionalSecrets.stream().filter(Objects::nonNull).filter(value -> !value.isEmpty())
                .filter(value -> !secrets.contains(value)).forEach(secrets::add);
        ManagedSession managed = new ManagedSession(UUID.randomUUID().toString(), ownerId,
                redactor.redactText(redactor.redactUrl(uri.toString()), secrets), LocalDateTime.now(), secrets);
        synchronized (admissionLock) {
            long activeGlobal = sessions.values().stream().filter(ManagedSession::isActive).count();
            long activeUser = sessions.values().stream().filter(item -> item.isActive() && Objects.equals(item.ownerId, ownerId)).count();
            if (activeGlobal >= MAX_ACTIVE_GLOBAL || activeUser >= MAX_ACTIVE_PER_USER)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "WebSocket session limit reached");
            if (sessions.size() >= 1000) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "WebSocket session capacity reached");
            sessions.put(managed.id, managed);
        }

        try {
            managed.connection = client.execute(uri, headers, socket -> handleConnection(managed, socket))
                    .subscribe(ignored -> { }, error -> fail(managed, error));
            managed.handshakeTimeout = Mono.delay(CONNECT_TIMEOUT).subscribe(ignored -> {
                if (!managed.ready.isDone() && managed.state.equals("CONNECTING")) {
                    fail(managed, new IllegalStateException("WebSocket handshake timed out"));
                    if (managed.connection != null) managed.connection.dispose();
                }
            });
            if (managed.ready.isDone() && managed.handshakeTimeout != null) managed.handshakeTimeout.dispose();
            managed.expiry = Mono.delay(SESSION_DURATION).subscribe(ignored -> closeInternal(managed, "Session duration limit reached"));
            return view(managed);
        } catch (Exception e) {
            if (managed.connection != null) managed.connection.dispose();
            sessions.remove(managed.id, managed);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "WebSocket handshake failed");
        }
    }

    void awaitConnectedForOwner(String id, Long ownerId, long timeoutMs) throws InterruptedException, java.util.concurrent.TimeoutException {
        ManagedSession session = ownedSessionForOwner(id, ownerId);
        try {
            session.ready.get(Math.max(1, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new IllegalStateException("WebSocket handshake failed");
        }
    }

    void sendForOwner(String id, Long ownerId, String message) {
        ManagedSession session = ownedSessionForOwner(id, ownerId);
        if (message == null || message.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Message exceeds the 64 KiB limit");
        if (!"CONNECTED".equals(session.state)) throw new IllegalStateException("WebSocket session is not connected");
        if (session.totalMessages.incrementAndGet() > MAX_TOTAL_MESSAGES) {
            closeInternal(session, "Session message limit reached");
            throw new IllegalStateException("WebSocket session message limit reached");
        }
        if (session.outbound.tryEmitNext(message).isFailure()) throw new IllegalStateException("WebSocket message could not be queued");
    }

    /** Waits for the next inbound frame after {@code afterSequence}; null means the bounded receive timed out. */
    MessageView awaitMessageForOwner(String id, Long ownerId, long afterSequence, long timeoutMs)
            throws InterruptedException {
        return ownedSessionForOwner(id, ownerId).awaitMessage(afterSequence, timeoutMs);
    }

    void closeForOwner(String id, Long ownerId) {
        ManagedSession session = ownedSessionForOwner(id, ownerId);
        closeInternal(session, "Suite case complete");
        if (session.connection != null) session.connection.dispose();
        if ("CLOSING".equals(session.state)) {
            session.state = "DISCONNECTED";
            session.closedAt = LocalDateTime.now();
            session.notifyStateChanged();
        }
    }

    SessionView getForOwner(String id, Long ownerId) {
        return view(ownedSessionForOwner(id, ownerId));
    }

    private Mono<Void> handleConnection(ManagedSession managed, WebSocketSession socket) {
        if (!managed.state.equals("CONNECTING")) {
            return socket.close(new CloseStatus(1000, "Session closed before handshake completed"));
        }
        managed.socket = socket;
        managed.state = "CONNECTED";
        managed.connectedAt = LocalDateTime.now();
        managed.handshakeHeaders = safeHeaders(socket.getHandshakeInfo().getHeaders(), managed.secrets);
        managed.ready.complete(null);

        Mono<Void> outgoing = socket.send(managed.outbound.asFlux().map(socket::textMessage));
        Mono<Void> incoming = socket.receive().doOnNext(message -> acceptMessage(managed, message)).then();
        return Mono.when(outgoing, incoming).doFinally(signal -> {
            managed.outbound.tryEmitComplete();
            if (!"ERROR".equals(managed.state)) managed.state = "DISCONNECTED";
            managed.closedAt = LocalDateTime.now();
            managed.notifyStateChanged();
        });
    }

    private void acceptMessage(ManagedSession managed, WebSocketMessage message) {
        if (message.getType() != WebSocketMessage.Type.TEXT) {
            closeInternal(managed, "Binary WebSocket messages are not supported");
            return;
        }
        String payload = message.getPayloadAsText();
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
            closeInternal(managed, "Incoming frame exceeded the 64 KiB limit");
            return;
        }
        if (managed.totalMessages.incrementAndGet() > MAX_TOTAL_MESSAGES) {
            closeInternal(managed, "Session message limit reached");
            return;
        }
        managed.addMessage("TEXT", redactor.redactText(payload, managed.secrets));
    }

    public SessionView send(String id, String message) {
        ManagedSession session = ownedSession(id);
        if (message == null || message.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Message exceeds the 64 KiB limit");
        if (!"CONNECTED".equals(session.state)) throw new ResponseStatusException(HttpStatus.CONFLICT, "WebSocket session is not connected");
        if (session.totalMessages.incrementAndGet() > MAX_TOTAL_MESSAGES) {
            closeInternal(session, "Session message limit reached");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Session message limit reached");
        }
        Sinks.EmitResult result = session.outbound.tryEmitNext(message);
        if (result.isFailure()) throw new ResponseStatusException(HttpStatus.CONFLICT, "WebSocket message could not be queued");
        return view(session);
    }

    public SessionView get(String id) { return view(ownedSession(id)); }

    public List<SessionView> list() {
        Long ownerId = users.requireUser().getId();
        return sessions.values().stream().filter(session -> Objects.equals(session.ownerId, ownerId))
                .sorted((left, right) -> right.createdAt.compareTo(left.createdAt)).map(this::view).toList();
    }

    public SessionView close(String id) {
        ManagedSession session = ownedSession(id);
        closeInternal(session, "Closed by user");
        return view(session);
    }

    private void closeInternal(ManagedSession session, String reason) {
        if (!session.isActive()) return;
        session.state = "CLOSING";
        session.ready.completeExceptionally(new IllegalStateException("WebSocket session closed"));
        session.notifyStateChanged();
        if (session.expiry != null) session.expiry.dispose();
        if (session.handshakeTimeout != null) session.handshakeTimeout.dispose();
        session.outbound.tryEmitComplete();
        WebSocketSession socket = session.socket;
        if (socket != null) socket.close(new CloseStatus(1000, reason.substring(0, Math.min(reason.length(), 120)))).subscribe(
                ignored -> { }, error -> { session.state = "DISCONNECTED"; session.closedAt = LocalDateTime.now(); });
        else {
            if (session.connection != null) session.connection.dispose();
            session.state = "DISCONNECTED";
            session.closedAt = LocalDateTime.now();
            session.notifyStateChanged();
        }
    }

    private void fail(ManagedSession session, Throwable error) {
        if ("CLOSING".equals(session.state) || "DISCONNECTED".equals(session.state)) return;
        session.state = "ERROR";
        session.error = error instanceof com.Aryan.APIX.service.OutboundPolicyBlockedException
                ? "Destination blocked by APiX network policy" : "WebSocket connection failed";
        session.ready.completeExceptionally(error);
        if (session.handshakeTimeout != null) session.handshakeTimeout.dispose();
        session.outbound.tryEmitComplete();
        session.closedAt = LocalDateTime.now();
        session.notifyStateChanged();
    }

    private ManagedSession ownedSession(String id) {
        User user = users.requireUser();
        return ownedSessionForOwner(id, user.getId());
    }

    private ManagedSession ownedSessionForOwner(String id, Long ownerId) {
        ManagedSession session = sessions.get(id);
        if (session == null || !Objects.equals(session.ownerId, ownerId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "WebSocket session not found");
        return session;
    }

    private SessionView view(ManagedSession session) {
        int status = session.state.equals("CONNECTED") || session.state.equals("CLOSING") || session.state.equals("DISCONNECTED") ? 101 : 0;
        return new SessionView(session.id, session.state, session.target, status, session.handshakeHeaders,
                session.connectedAt, session.createdAt.plus(SESSION_DURATION), session.snapshotMessages(), session.error);
    }

    private HttpHeaders validateHeaders(Map<String, String> values) {
        HttpHeaders headers = new HttpHeaders();
        if (values == null) return headers;
        if (values.size() > MAX_HEADER_COUNT) throw badRequest("Too many WebSocket handshake headers");
        values.forEach((name, value) -> {
            if (name == null || value == null || !HEADER_TOKEN.matcher(name).matches()
                    || value.length() > MAX_HEADER_BYTES || value.getBytes(StandardCharsets.UTF_8).length > MAX_HEADER_BYTES
                    || value.contains("\r") || value.contains("\n")) throw badRequest("Invalid WebSocket handshake header");
            String normalized = name.toLowerCase(java.util.Locale.ROOT);
            if (normalized.equals("host") || normalized.equals("connection") || normalized.equals("upgrade")
                    || normalized.startsWith("sec-websocket-") || normalized.equals("content-length"))
                throw badRequest("WebSocket protocol headers are managed by APiX");
            headers.set(name, value);
        });
        return headers;
    }

    private List<String> requestSecrets(Map<String, String> values) {
        if (values == null) return List.of();
        return values.entrySet().stream().filter(entry -> SECRET_HEADER.matcher(entry.getKey()).matches())
                .map(Map.Entry::getValue).filter(value -> value != null && !value.isEmpty()).distinct().toList();
    }

    private Map<String, String> safeHeaders(HttpHeaders values, List<String> secrets) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((name, entries) -> {
            if (!entries.isEmpty()) {
                String value = redactor.redactHeaders(Map.of(name, entries.getFirst())).get(name);
                result.put(name, redactor.redactText(value, secrets));
            }
        });
        return Map.copyOf(result);
    }

    private void evictOldSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minus(SESSION_DURATION.multipliedBy(2));
        sessions.values().removeIf(session -> !session.isActive() && session.createdAt.isBefore(cutoff));
    }

    @PreDestroy
    public void closeAll() { sessions.values().forEach(session -> closeInternal(session, "Application shutdown")); }

    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    private static final class ManagedSession {
        private final String id;
        private final Long ownerId;
        private final String target;
        private final LocalDateTime createdAt;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(100));
        private final List<String> secrets;
        private final AtomicLong sequence = new AtomicLong();
        private final java.util.concurrent.atomic.AtomicInteger totalMessages = new java.util.concurrent.atomic.AtomicInteger();
        private final ArrayDeque<MessageView> messages = new ArrayDeque<>();
        private volatile String state = "CONNECTING";
        private volatile String error;
        private volatile LocalDateTime connectedAt;
        private volatile LocalDateTime closedAt;
        private volatile Map<String, String> handshakeHeaders = Map.of();
        private volatile WebSocketSession socket;
        private volatile Disposable connection;
        private volatile Disposable expiry;
        private volatile Disposable handshakeTimeout;

        private ManagedSession(String id, Long ownerId, String target, LocalDateTime createdAt, List<String> secrets) {
            this.id = id; this.ownerId = ownerId; this.target = target; this.createdAt = createdAt; this.secrets = secrets;
        }

        private boolean isActive() { return state.equals("CONNECTING") || state.equals("CONNECTED") || state.equals("CLOSING"); }
        private synchronized void addMessage(String type, String payload) {
            messages.addLast(new MessageView(sequence.incrementAndGet(), LocalDateTime.now(), type, payload));
            while (messages.size() > MAX_RETAINED_MESSAGES) messages.removeFirst();
            notifyAll();
        }
        private synchronized List<MessageView> snapshotMessages() { return new ArrayList<>(messages); }
        private synchronized MessageView awaitMessage(long afterSequence, long timeoutMs) throws InterruptedException {
            long timeoutNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
            long deadline = System.nanoTime() + timeoutNanos;
            while (true) {
                MessageView next = messages.stream().filter(message -> message.sequence() > afterSequence).findFirst().orElse(null);
                if (next != null) {
                    if (next.sequence() > afterSequence + 1) throw new IllegalStateException("Inbound message buffer limit was exceeded");
                    return next;
                }
                if (state.equals("ERROR") || state.equals("DISCONNECTED") || state.equals("CLOSING"))
                    throw new IllegalStateException("WebSocket closed before the expected message arrived");
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
        private synchronized void notifyStateChanged() { notifyAll(); }
    }
}
