package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.LocalAgentPairing;
import com.Aryan.APIX.entity.LocalTestAgent;
import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.entity.User;
import com.Aryan.APIX.model.*;
import com.Aryan.APIX.model.LocalAgentDtos.*;
import com.Aryan.APIX.repository.LocalAgentPairingRepository;
import com.Aryan.APIX.repository.LocalTestAgentRepository;
import com.Aryan.APIX.repository.RequestHistoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PostConstruct;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Service
public class LocalTestAgentService {
    private static final Duration PAIRING_TTL = Duration.ofMinutes(5);
    private static final Duration ONLINE_WINDOW = Duration.ofSeconds(45);
    private static final int MAX_TARGETS = 20;
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_JOBS_PER_AGENT = 3;
    private static final Pattern SECRET_HEADER = Pattern.compile("(?i).*(authorization|cookie|token|secret|password|credential|api[-_]?key).*");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("host", "connection", "content-length", "transfer-encoding", "upgrade", "proxy-authorization", "proxy-connection", "te", "trailer");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Tracer TRACER = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("APIX-local-agent");

    private final LocalTestAgentRepository agents;
    private final LocalAgentPairingRepository pairings;
    private final CurrentUserService users;
    private final RequestHistoryRepository history;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper mapper;
    @Value("${apix.otel.ui-base-url:https://all-in-one-production-a9d1.up.railway.app/trace/}")
    private String traceUiBaseUrl = "https://all-in-one-production-a9d1.up.railway.app/trace/";
    private final ConcurrentHashMap<Long, AgentChannel> channels = new ConcurrentHashMap<>();

    public LocalTestAgentService(LocalTestAgentRepository agents, LocalAgentPairingRepository pairings,
                                 CurrentUserService users, RequestHistoryRepository history,
                                 SensitiveDataRedactor redactor, ObjectMapper mapper) {
        this.agents = agents; this.pairings = pairings; this.users = users; this.history = history;
        this.redactor = redactor; this.mapper = mapper;
    }

    @Transactional
    public PairingCreated createPairing(PairingCreateRequest request) {
        User owner = users.requireUser();
        if (request == null || request.name() == null || request.name().isBlank() || request.name().length() > 120)
            throw badRequest("A valid agent name is required");
        LocalDateTime now = LocalDateTime.now();
        pairings.deleteExpiredOrConsumed(now);
        if (pairings.countByUser_IdAndConsumedAtIsNullAndExpiresAtAfter(owner.getId(), now) >= 5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many pending agent pairings");
        if (agents.countByUser_IdAndStatus(owner.getId(), "PAIRED") >= 10)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many paired agents");
        List<TargetGrant> grants = validateGrants(request.allowedTargets());
        String code = randomSecret();
        LocalAgentPairing pairing = new LocalAgentPairing();
        pairing.setUser(owner); pairing.setAgentName(request.name().trim()); pairing.setPairingHash(hash(code));
        pairing.setAllowedTargetsJson(write(grants)); pairing.setCreatedAt(now);
        pairing.setExpiresAt(now.plus(PAIRING_TTL));
        pairings.save(pairing);
        return new PairingCreated(code, pairing.getExpiresAt());
    }

    @PostConstruct
    public void restorePairedAgentChannels() {
        agents.findByStatus("PAIRED").forEach(agent -> channels.putIfAbsent(agent.getId(), new AgentChannel()));
    }

    @Transactional
    public PairResponse pair(PairRequest request) {
        if (request == null || request.pairingCode() == null || request.pairingCode().length() > 100)
            throw unauthorizedAgent();
        LocalAgentPairing pairing = pairings.findByPairingHash(hash(request.pairingCode())).orElseThrow(this::unauthorizedAgent);
        LocalDateTime now = LocalDateTime.now();
        if (pairings.consumeOnce(pairing.getId(), now) != 1) throw unauthorizedAgent();
        String credential = randomSecret();
        LocalTestAgent agent = new LocalTestAgent();
        agent.setUser(pairing.getUser()); agent.setName(pairing.getAgentName()); agent.setCredentialHash(hash(credential));
        agent.setAllowedTargetsJson(pairing.getAllowedTargetsJson()); agent.setStatus("PAIRED"); agent.setCreatedAt(now);
        agent = agents.save(agent);
        channels.put(agent.getId(), new AgentChannel());
        return new PairResponse(agent.getId(), credential, readGrants(agent.getAllowedTargetsJson()));
    }

    public List<AgentView> list() {
        User owner = users.requireUser();
        return agents.findByUserOrderByCreatedAtDesc(owner).stream().map(this::view).toList();
    }

    @Transactional
    public AgentView revoke(long agentId) {
        User owner = users.requireUser();
        LocalTestAgent agent = agents.findByIdAndUser(agentId, owner).orElseThrow(() -> notFound("Agent not found"));
        if (!"REVOKED".equals(agent.getStatus())) {
            agent.setStatus("REVOKED"); agent.setRevokedAt(LocalDateTime.now()); agents.save(agent);
        }
        AgentChannel channel = channels.remove(agentId);
        if (channel != null) channel.failAll(new IllegalStateException("Agent revoked"));
        return view(agent);
    }

    public AgentView view(LocalTestAgent agent) {
        String state = "REVOKED".equals(agent.getStatus()) ? "REVOKED"
                : agent.getLastSeenAt() != null && agent.getLastSeenAt().isAfter(LocalDateTime.now().minus(ONLINE_WINDOW)) ? "PAIRED" : "DISCONNECTED";
        return new AgentView(agent.getId(), agent.getName(), state, readGrants(agent.getAllowedTargetsJson()),
                agent.getCreatedAt(), agent.getLastSeenAt());
    }

    public AgentJob poll(String credential, long waitMs) {
        LocalTestAgent agent = authenticateAndTouch(credential);
        AgentChannel channel = channels.get(agent.getId());
        if (channel == null) throw unavailableAgent();
        PendingJob job;
        try { job = channel.queue.poll(Math.max(0, Math.min(waitMs, 20_000)), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        if (job == null) { authenticateAndTouch(credential); return null; }
        channel.inFlight.put(job.job.id(), job);
        try { authenticateAndTouch(credential); }
        catch (RuntimeException revoked) { channel.inFlight.remove(job.job.id()); job.result.completeExceptionally(revoked); throw revoked; }
        return job.job;
    }

    public void acceptResult(String credential, String jobId, AgentJobResult result) {
        LocalTestAgent agent = authenticateAndTouch(credential);
        AgentChannel channel = channels.get(agent.getId());
        PendingJob job = channel == null ? null : channel.inFlight.remove(jobId);
        if (job == null) throw notFound("Agent job not found");
        try {
            if (result == null || result.statusCode() < 0 || result.statusCode() > 599 || result.elapsedMs() < 0
                    || result.body() != null && result.body().getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES
                    || result.headers() != null && (result.headers().size() > 100 || result.headers().values().stream().anyMatch(v -> v == null || v.length() > 8192)))
                throw badRequest("Agent result exceeds APiX limits");
            job.result.complete(result);
        } catch (RuntimeException invalid) {
            job.result.completeExceptionally(new IllegalArgumentException("Agent result was invalid"));
            throw invalid;
        } finally { job.slot.release(); }
    }

    public ApiExecutionResponse execute(long agentId, ApiRequest request, Long ownerId) {
        if (request == null) throw badRequest("Request is required");
        LocalTestAgent agent = agents.findById(agentId).orElseThrow(() -> notFound("Agent not found"));
        User owner;
        if (ownerId == null) {
            owner = users.requireUser();
            if (!Objects.equals(agent.getUser().getId(), owner.getId())) throw notFound("Agent not found");
        } else {
            if (!Objects.equals(agent.getUser().getId(), ownerId)) throw notFound("Agent not found");
            owner = agent.getUser();
        }
        if (!"PAIRED".equals(agent.getStatus())) throw new ResponseStatusException(HttpStatus.GONE, "Agent is revoked");
        if (agent.getLastSeenAt() == null || agent.getLastSeenAt().isBefore(LocalDateTime.now().minus(ONLINE_WINDOW))) throw unavailableAgent();
        validateRequest(request, readGrants(agent.getAllowedTargetsJson()));
        AgentChannel channel = channels.get(agentId);
        if (channel == null) throw unavailableAgent();
        if (!channel.slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Agent execution capacity is full");
        long timeoutMs = request.getTimeoutMs() == null ? 15_000 : request.getTimeoutMs();
        Map<String, String> headers = request.getHeaders() == null ? Map.of() : Map.copyOf(request.getHeaders());
        AgentJob jobDto = new AgentJob(UUID.randomUUID().toString(), request.getMethod().toUpperCase(Locale.ROOT), request.getUrl(), headers,
                request.getBody(), timeoutMs, MAX_RESPONSE_BYTES);
        PendingJob job = new PendingJob(jobDto, channel.slots);
        if (!channel.queue.offer(job)) { channel.slots.release(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent job queue is full"); }

        String executionId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        Span span = TRACER.spanBuilder("Local Agent HTTP Request").startSpan();
        String traceId = span.getSpanContext().getTraceId();
        try {
            try (Scope ignored = span.makeCurrent()) {
            AgentJobResult result = job.result.get(timeoutMs + 5_000, TimeUnit.MILLISECONDS);
            long elapsed = Math.max(result.elapsedMs(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            String responseBody = result.body() == null ? "" : result.body();
            List<String> secrets = sensitiveValues(request);
            Map<String, String> redactedHeaders = redactor.redactHeaders(result.headers() == null ? Map.of() : result.headers(), secrets);
            responseBody = redactor.redactText(responseBody, secrets);
            ApiResponse apiResponse = new ApiResponse(result.statusCode(), redactedHeaders, responseBody, elapsed, Map.of());
            ApiExecutionResponse response = new ApiExecutionResponse();
            response.setApiResponse(apiResponse); response.setExecutionId(executionId); response.setTraceId(traceId);
            response.setTraceUrl(traceUiBaseUrl.replaceAll("/+$", "") + "/" + traceId);
            response.setExecutionError(result.error() != null || result.statusCode() == 0);
            TimingTrace timing = new TimingTrace(); timing.setRequestStart(System.currentTimeMillis() - elapsed); timing.setTotalTime(elapsed);
            response.setTimingTrace(timing);
            if (result.error() != null) response.setErrorAnalysis(new ErrorAnalysis("Local Agent Error", "The local agent could not complete the request", "Check agent connectivity and its local target approval"));
            saveHistory(owner, request, apiResponse, executionId, traceId);
            return response;
            }
        } catch (TimeoutException e) {
            if (channel.queue.remove(job)) job.slot.release();
            else job.uncertain.set(true);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Local agent request timed out; outcome may be uncertain");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (channel.queue.remove(job)) job.slot.release(); else job.uncertain.set(true);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Local agent request was interrupted; outcome may be uncertain");
        } catch (ExecutionException e) {
            throw unavailableAgent();
        } finally {
            span.end();
        }
    }

    private LocalTestAgent authenticateAndTouch(String credential) {
        if (credential == null || credential.length() > 100) throw unauthorizedAgent();
        LocalTestAgent agent = agents.findByCredentialHash(hash(credential)).orElseThrow(this::unauthorizedAgent);
        if (!"PAIRED".equals(agent.getStatus())) throw unauthorizedAgent();
        agent.setLastSeenAt(LocalDateTime.now());
        agents.save(agent);
        return agent;
    }

    private void validateRequest(ApiRequest request, List<TargetGrant> grants) {
        if (request.getMethod() == null || !METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) throw badRequest("Unsupported HTTP method");
        if (request.getUrl() == null || request.getUrl().length() > 4000) throw badRequest("Invalid target URL");
        URI uri;
        try { uri = URI.create(request.getUrl()); } catch (Exception e) { throw badRequest("Invalid target URL"); }
        String scheme = Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT);
        String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || host.isBlank() || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getRawAuthority() == null || uri.getRawAuthority().contains("\\") || uri.getRawAuthority().contains("%")) throw badRequest("Invalid target URL");
        int port = uri.getPort() == -1 ? scheme.equals("https") ? 443 : 80 : uri.getPort();
        if (port < 1 || port > 65535 || grants.stream().noneMatch(grant -> grant.port() == port && grant.host().equals(host)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Target host and port are not approved for this agent");
        if (request.getBody() != null && request.getBody().getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds agent limit");
        Map<String, String> headers = request.getHeaders() == null ? Map.of() : request.getHeaders();
        if (headers.size() > 50 || headers.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null || e.getKey().length() > 200
                || e.getValue().getBytes(StandardCharsets.UTF_8).length > 8192 || e.getValue().contains("\r") || e.getValue().contains("\n")
                || FORBIDDEN_HEADERS.contains(e.getKey().toLowerCase(Locale.ROOT)))) throw badRequest("Request headers exceed agent limits");
        if (request.getTimeoutMs() != null && (request.getTimeoutMs() < 100 || request.getTimeoutMs() > 30_000)) throw badRequest("timeoutMs must be between 100 and 30000");
        if (request.isDiagnosticMode()) throw badRequest("Diagnostic retries are disabled for agent requests");
    }

    private List<TargetGrant> validateGrants(List<TargetGrant> input) {
        if (input == null || input.isEmpty() || input.size() > MAX_TARGETS) throw badRequest("Approve between 1 and 20 loopback host/port targets");
        Set<TargetGrant> unique = new LinkedHashSet<>();
        for (TargetGrant grant : input) {
            if (grant == null || grant.host() == null || grant.port() < 1 || grant.port() > 65535) throw badRequest("Invalid target grant");
            String host = grant.host().toLowerCase(Locale.ROOT);
            if (!host.equals("localhost") && !isLoopbackLiteral(host)) throw badRequest("Only localhost, 127.0.0.1, and ::1 may be granted to this agent");
            unique.add(new TargetGrant(host, grant.port()));
        }
        if (unique.size() != input.size()) throw badRequest("Target grants must be unique");
        return List.copyOf(unique);
    }

    private boolean isLoopbackLiteral(String host) {
        try { return InetAddress.getByName(host).isLoopbackAddress() && (host.equals("127.0.0.1") || host.equals("::1")); }
        catch (Exception e) { return false; }
    }

    private void saveHistory(User owner, ApiRequest request, ApiResponse response, String executionId, String traceId) {
        RequestHistory entry = new RequestHistory();
        List<String> secrets = sensitiveValues(request);
        entry.setUser(owner); entry.setMethod(request.getMethod()); entry.setUrl(redactor.redactUrl(redactor.redactText(request.getUrl(), secrets)));
        entry.setStatusCode(response.getStatusCode()); entry.setResponseTime(response.getResponseTime()); entry.setExecutionId(executionId); entry.setTraceId(traceId);
        try { entry.setHeaders(mapper.writeValueAsString(redactor.redactHeaders(request.getHeaders() == null ? Map.of() : request.getHeaders(), secrets))); }
        catch (Exception e) { entry.setHeaders("{}"); }
        entry.setBody(redactor.redactText(response.getBody(), secrets));
        history.save(entry);
    }

    private List<String> requestSecrets(Map<String, String> headers) {
        if (headers == null) return List.of();
        return headers.entrySet().stream().filter(e -> SECRET_HEADER.matcher(e.getKey()).matches()).map(Map.Entry::getValue).filter(v -> v != null && !v.isEmpty()).distinct().toList();
    }

    private List<String> sensitiveValues(ApiRequest request) {
        LinkedHashSet<String> values = new LinkedHashSet<>(requestSecrets(request.getHeaders()));
        if (request.getSensitiveValues() != null) request.getSensitiveValues().stream().filter(v -> v != null && !v.isEmpty()).forEach(values::add);
        return List.copyOf(values);
    }

    private List<TargetGrant> readGrants(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Saved agent grants are unreadable"); }
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save agent grants"); }
    }

    private String randomSecret() { byte[] value = new byte[32]; RANDOM.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private ResponseStatusException unauthorizedAgent() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Agent credential is invalid, expired, or revoked"); }
    private ResponseStatusException unavailableAgent() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Local agent is disconnected or unavailable"); }

    private static final class AgentChannel {
        private final ArrayBlockingQueue<PendingJob> queue = new ArrayBlockingQueue<>(MAX_JOBS_PER_AGENT);
        private final ConcurrentHashMap<String, PendingJob> inFlight = new ConcurrentHashMap<>();
        private final Semaphore slots = new Semaphore(MAX_JOBS_PER_AGENT);
        private void failAll(Throwable error) {
            PendingJob job;
            while ((job = queue.poll()) != null) { job.result.completeExceptionally(error); job.slot.release(); }
            inFlight.forEach((id, pending) -> { if (inFlight.remove(id, pending)) { pending.result.completeExceptionally(error); pending.slot.release(); } });
        }
    }

    private static final class PendingJob {
        private final AgentJob job;
        private final CompletableFuture<AgentJobResult> result = new CompletableFuture<>();
        private final AtomicBoolean uncertain = new AtomicBoolean();
        private final Semaphore slot;
        private PendingJob(AgentJob job, Semaphore slot) { this.job = job; this.slot = slot; }
    }
}
