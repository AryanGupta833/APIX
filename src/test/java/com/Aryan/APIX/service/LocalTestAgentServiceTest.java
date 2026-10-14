package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.LocalAgentPairing;
import com.Aryan.APIX.entity.LocalTestAgent;
import com.Aryan.APIX.entity.User;
import com.Aryan.APIX.model.ApiRequest;
import com.Aryan.APIX.model.LocalAgentDtos.*;
import com.Aryan.APIX.model.ApiExecutionResponse;
import com.Aryan.APIX.repository.LocalAgentPairingRepository;
import com.Aryan.APIX.repository.LocalTestAgentRepository;
import com.Aryan.APIX.repository.RequestHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalTestAgentServiceTest {
    private final LocalTestAgentRepository agents = mock(LocalTestAgentRepository.class);
    private final LocalAgentPairingRepository pairings = mock(LocalAgentPairingRepository.class);
    private final CurrentUserService users = mock(CurrentUserService.class);
    private final RequestHistoryRepository history = mock(RequestHistoryRepository.class);
    private final LocalTestAgentService service = new LocalTestAgentService(agents, pairings, users, history,
            new SensitiveDataRedactor(), JsonMapper.builder().build());

    @Test
    void pairingStoresOnlyHashAndAtomicallyConsumesCodeOnce() {
        User owner = owner(7L); when(users.requireUser()).thenReturn(owner);
        when(pairings.countByUser_IdAndConsumedAtIsNullAndExpiresAtAfter(eq(7L), any())).thenReturn(0L);
        when(agents.countByUser_IdAndStatus(7L, "PAIRED")).thenReturn(0L);
        when(pairings.deleteExpiredOrConsumed(any())).thenReturn(0);
        when(pairings.save(any())).thenAnswer(call -> call.getArgument(0));
        var issued = service.createPairing(new PairingCreateRequest("dev laptop", List.of(new TargetGrant("127.0.0.1", 8080))));
        var savedPairing = org.mockito.ArgumentCaptor.forClass(LocalAgentPairing.class);
        verify(pairings).save(savedPairing.capture());
        assertNotEquals(issued.pairingCode(), savedPairing.getValue().getPairingHash());
        assertEquals(64, savedPairing.getValue().getPairingHash().length());

        LocalAgentPairing pairing = savedPairing.getValue(); pairing.setId(22L); pairing.setUser(owner);
        when(pairings.findByPairingHash(anyString())).thenReturn(Optional.of(pairing));
        AtomicInteger uses = new AtomicInteger();
        when(pairings.consumeOnce(eq(22L), any())).thenAnswer(call -> uses.compareAndSet(0, 1) ? 1 : 0);
        when(agents.save(any())).thenAnswer(call -> { LocalTestAgent value = call.getArgument(0); value.setId(9L); return value; });

        var paired = service.pair(new PairRequest(issued.pairingCode()));
        assertEquals(9L, paired.agentId());
        var agentCaptor = org.mockito.ArgumentCaptor.forClass(LocalTestAgent.class);
        verify(agents).save(agentCaptor.capture());
        assertNotEquals(paired.credential(), agentCaptor.getValue().getCredentialHash());
        assertEquals(List.of(new TargetGrant("127.0.0.1", 8080)), paired.allowedTargets());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> service.pair(new PairRequest(issued.pairingCode()))).getStatusCode());
        verify(agents, times(1)).save(any(LocalTestAgent.class));
    }

    @Test
    void concurrentlyConsumedPairingCreatesOnlyOneAgent() throws Exception {
        LocalAgentPairing pairing = new LocalAgentPairing(); pairing.setId(5L); pairing.setUser(owner(2L));
        pairing.setAgentName("dev"); pairing.setAllowedTargetsJson("[{\"host\":\"localhost\",\"port\":3000}]");
        when(pairings.findByPairingHash(anyString())).thenReturn(Optional.of(pairing));
        AtomicInteger consumed = new AtomicInteger();
        when(pairings.consumeOnce(eq(5L), any())).thenAnswer(call -> consumed.compareAndSet(0, 1) ? 1 : 0);
        when(agents.save(any())).thenAnswer(call -> { LocalTestAgent agent = call.getArgument(0); agent.setId(1L); return agent; });
        CountDownLatch start = new CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { await(start); return pairOrNull("same-code"); });
            var second = pool.submit(() -> { await(start); return pairOrNull("same-code"); });
            start.countDown();
            int successes = (first.get() == null ? 0 : 1) + (second.get() == null ? 0 : 1);
            assertEquals(1, successes);
            verify(agents, times(1)).save(any(LocalTestAgent.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void wrongOwnerCannotRevokeAgent() {
        when(users.requireUser()).thenReturn(owner(10L));
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> service.revoke(99L));
        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
    }

    @Test
    void agentRejectsUnapprovedHostAndPortBeforeEnqueue() throws Exception {
        User owner = owner(4L); when(users.requireUser()).thenReturn(owner);
        LocalTestAgent agent = new LocalTestAgent(); agent.setId(3L); agent.setUser(owner); agent.setStatus("PAIRED");
        agent.setLastSeenAt(LocalDateTime.now()); agent.setAllowedTargetsJson("[{\"host\":\"127.0.0.1\",\"port\":8080}]");
        when(agents.findById(3L)).thenReturn(Optional.of(agent));
        ApiRequest request = new ApiRequest(); request.setMethod("GET"); request.setUrl("http://127.0.0.1:8081/private");
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> service.execute(3L, request, null));
        assertEquals(HttpStatus.FORBIDDEN, failure.getStatusCode());
    }

    @Test
    void agentJobUsesStructuredResultAndRedactsResponseEvidence() throws Exception {
        User owner = owner(12L); when(users.requireUser()).thenReturn(owner);
        LocalAgentPairing pairing = new LocalAgentPairing(); pairing.setId(6L); pairing.setUser(owner);
        pairing.setAgentName("local"); pairing.setAllowedTargetsJson("[{\"host\":\"127.0.0.1\",\"port\":8080}]");
        when(pairings.findByPairingHash(anyString())).thenReturn(Optional.of(pairing));
        when(pairings.consumeOnce(eq(6L), any())).thenReturn(1);
        LocalTestAgent agent = new LocalTestAgent(); agent.setId(15L); agent.setUser(owner); agent.setStatus("PAIRED");
        agent.setName("local"); agent.setAllowedTargetsJson(pairing.getAllowedTargetsJson()); agent.setCreatedAt(LocalDateTime.now());
        when(agents.save(any())).thenAnswer(call -> {
            LocalTestAgent saved = call.getArgument(0);
            saved.setId(15L);
            return saved;
        });
        when(agents.findById(15L)).thenReturn(Optional.of(agent));
        when(agents.findByCredentialHash(anyString())).thenReturn(Optional.of(agent));
        var paired = service.pair(new PairRequest("one-use-code"));
        agent.setLastSeenAt(LocalDateTime.now());

        ApiRequest request = new ApiRequest(); request.setMethod("GET"); request.setUrl("http://127.0.0.1:8080/health");
        request.setHeaders(Map.of("Authorization", "top-secret"));
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> service.execute(15L, request, null));
            AgentJob delivered = service.poll(paired.credential(), 1000);
            assertNotNull(delivered);
            assertEquals("GET", delivered.method());
            assertEquals("http://127.0.0.1:8080/health", delivered.url());
            service.acceptResult(paired.credential(), delivered.id(), new AgentJobResult(200,
                    Map.of("Set-Cookie", "top-secret"), "{\"token\":\"top-secret\"}", 4, null));
            ApiExecutionResponse result = pending.get(2, TimeUnit.SECONDS);
            assertEquals(200, result.getApiResponse().getStatusCode());
            assertEquals("{\"token\":\"[REDACTED]\"}", result.getApiResponse().getBody());
            assertEquals("[REDACTED]", result.getApiResponse().getHeaders().get("Set-Cookie"));
            verify(history).save(any());
        } finally { worker.shutdownNow(); }
    }

    private PairResponse pairOrNull(String code) {
        try { return service.pair(new PairRequest(code)); }
        catch (ResponseStatusException expectedLoser) { return null; }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static User owner(long id) { User user = new User(); user.setId(id); user.setEmail("user" + id + "@example.test"); return user; }
}
