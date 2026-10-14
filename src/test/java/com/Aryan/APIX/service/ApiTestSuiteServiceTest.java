package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.ApiTestSuite;
import com.Aryan.APIX.entity.ApiSuiteRun;
import com.Aryan.APIX.model.ApiExecutionResponse;
import com.Aryan.APIX.model.ApiRequest;
import com.Aryan.APIX.model.ApiResponse;
import com.Aryan.APIX.model.SuiteDtos.*;
import com.Aryan.APIX.model.WebSocketDtos.MessageView;
import com.Aryan.APIX.model.WebSocketDtos.SessionView;
import com.Aryan.APIX.entity.User;
import com.Aryan.APIX.repository.ApiSuiteRunRepository;
import com.Aryan.APIX.repository.ApiTestSuiteRepository;
import com.Aryan.APIX.repository.RequestHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiTestSuiteServiceTest {
    private final ApiTestSuiteRepository suites = mock(ApiTestSuiteRepository.class);
    private final ApiSuiteRunRepository runs = mock(ApiSuiteRunRepository.class);
    private final RequestHistoryRepository history = mock(RequestHistoryRepository.class);
    private final CurrentUserService users = mock(CurrentUserService.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final RequestExecutionService executor = mock(RequestExecutionService.class);
    private final PerformanceRegressionService performance = mock(PerformanceRegressionService.class);
    private final AtomicReference<ApiSuiteRun> persistedRun = new AtomicReference<>();
    private final ThreadPoolTaskExecutor runExecutor = new ThreadPoolTaskExecutor() {
        @Override public Future<?> submit(Runnable task) { task.run(); return java.util.concurrent.CompletableFuture.completedFuture(null); }
    };
    private final ApiTestSuiteService service = new ApiTestSuiteService(suites, runs, history, users,
            executor, new ApiAssertionService(mapper), new SensitiveDataRedactor(), mapper,
            performance, runExecutor);

    @BeforeEach
    void allowControlledWorkerClaim() { when(runs.claimQueued(anyLong(), any())).thenReturn(1); }

    @Test
    void suiteLookupIsScopedToAuthenticatedOwner() {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        when(suites.findByIdAndUser(42L, owner)).thenReturn(Optional.empty());
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.get(42));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(suites).findByIdAndUser(42L, owner);
    }

    @Test
    void suiteRunLookupIsScopedToAuthenticatedOwner() {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        when(runs.findByIdAndUser(91L, owner)).thenReturn(Optional.empty());
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.getRun(91));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(runs).findByIdAndUser(91L, owner);
    }

    @Test
    void queuedSubmissionReturnsPersistedSnapshotBeforeControlledWorkerStarts() throws Exception {
        User owner = new User(); owner.setId(401L); when(users.requireUser()).thenReturn(owner);
        ApiTestSuite suite = suite(owner, "CONTINUE", List.of(testCase("case-1", "request", "GET", "https://api.example.test/", List.of(), List.of(), java.util.Map.of())));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        when(runs.save(any(ApiSuiteRun.class))).thenAnswer(invocation -> { ApiSuiteRun value = invocation.getArgument(0); value.setId(88L); return value; });
        AtomicReference<Runnable> task = new AtomicReference<>();
        ThreadPoolTaskExecutor heldExecutor = new ThreadPoolTaskExecutor() {
            @Override public Future<?> submit(Runnable value) { task.set(value); return java.util.concurrent.CompletableFuture.completedFuture(null); }
        };
        ApiTestSuiteService heldService = new ApiTestSuiteService(suites, runs, history, users, executor,
                new ApiAssertionService(mapper), new SensitiveDataRedactor(), mapper, mock(PerformanceRegressionService.class), heldExecutor);

        RunView accepted = heldService.run(8L, new RunRequest(java.util.Map.of(), List.of()));

        assertEquals("QUEUED", accepted.status());
        assertEquals(1, accepted.totalCases());
        assertEquals(0, accepted.completedCases());
        assertNotNull(task.get());
        verify(executor, never()).execute(any(ApiRequest.class));
    }

    @Test
    void onlyTheFirstConditionalWorkerClaimExecutesTheRun() throws Exception {
        User owner = new User(); owner.setId(402L); when(users.requireUser()).thenReturn(owner);
        ApiTestSuite suite = suite(owner, "CONTINUE", List.of(testCase("case-1", "request", "GET", "https://api.example.test/", List.of(), List.of(), java.util.Map.of())));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        when(runs.save(any(ApiSuiteRun.class))).thenAnswer(invocation -> { ApiSuiteRun value = invocation.getArgument(0); value.setId(89L); persistedRun.set(value); return value; });
        AtomicReference<Runnable> task = new AtomicReference<>();
        ThreadPoolTaskExecutor heldExecutor = new ThreadPoolTaskExecutor() {
            @Override public Future<?> submit(Runnable value) { task.set(value); return java.util.concurrent.CompletableFuture.completedFuture(null); }
        };
        when(runs.claimQueued(eq(89L), any())).thenReturn(1, 0);
        when(executor.execute(any(ApiRequest.class))).thenReturn(execution(200, "ok"));
        ApiTestSuiteService heldService = new ApiTestSuiteService(suites, runs, history, users, executor,
                new ApiAssertionService(mapper), new SensitiveDataRedactor(), mapper, mock(PerformanceRegressionService.class), heldExecutor);

        heldService.run(8L, new RunRequest(java.util.Map.of(), List.of()));
        task.get().run();
        task.get().run();

        verify(runs, times(2)).claimQueued(eq(89L), any());
        verify(executor, times(1)).execute(any(ApiRequest.class));
        assertEquals("PASSED", persistedRun.get().getStatus());
    }

    @Test
    void cancellationBetweenCasesPreventsTheNextOutboundRequest() throws Exception {
        User owner = new User(); owner.setId(405L); when(users.requireUser()).thenReturn(owner);
        ApiTestSuite suite = suite(owner, "CONTINUE", List.of(
                testCase("first", "first", "GET", "https://api.example.test/one", List.of(), List.of(), java.util.Map.of()),
                testCase("second", "second", "GET", "https://api.example.test/two", List.of(), List.of(), java.util.Map.of())));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        when(runs.claimQueued(eq(96L), any())).thenAnswer(invocation -> { persistedRun.get().setStatus("RUNNING"); return 1; });
        when(runs.findByIdAndUser(96L, owner)).thenAnswer(invocation -> Optional.ofNullable(persistedRun.get()));
        when(runs.findById(96L)).thenAnswer(invocation -> Optional.ofNullable(persistedRun.get()));
        when(runs.requestRunningCancellation(96L)).thenAnswer(invocation -> { persistedRun.get().setStatus("CANCEL_REQUESTED"); return 1; });
        AtomicBoolean cancellationSent = new AtomicBoolean();
        when(runs.save(any(ApiSuiteRun.class))).thenAnswer(invocation -> {
            ApiSuiteRun value = invocation.getArgument(0); value.setId(96L); persistedRun.set(value);
            if (value.getCompletedCases() == 1 && cancellationSent.compareAndSet(false, true)) service.cancelRun(96L);
            return value;
        });
        when(executor.execute(any(ApiRequest.class))).thenReturn(execution(200, "ok"));

        service.run(8L, new RunRequest(java.util.Map.of(), List.of()));

        verify(executor, times(1)).execute(any(ApiRequest.class));
        assertEquals("CANCELLED", persistedRun.get().getStatus());
        assertEquals(1, persistedRun.get().getCompletedCases());
    }

    @Test
    void concurrentSubmissionsCannotExceedThePerUserActiveLimit() throws Exception {
        User owner = new User(); owner.setId(403L); when(users.requireUser()).thenReturn(owner);
        ApiTestSuite suite = suite(owner, "CONTINUE", List.of(testCase("case-1", "request", "GET", "https://api.example.test/", List.of(), List.of(), java.util.Map.of())));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        AtomicLong nextRunId = new AtomicLong(100L);
        AtomicLong activeCount = new AtomicLong();
        when(runs.countByUserAndStatusIn(eq(owner), anyList())).thenAnswer(invocation -> activeCount.get());
        when(runs.save(any(ApiSuiteRun.class))).thenAnswer(invocation -> { ApiSuiteRun value = invocation.getArgument(0); value.setId(nextRunId.getAndIncrement()); activeCount.incrementAndGet(); return value; });
        ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>();
        ThreadPoolTaskExecutor heldExecutor = new ThreadPoolTaskExecutor() {
            @Override public Future<?> submit(Runnable value) { queued.add(value); return java.util.concurrent.CompletableFuture.completedFuture(null); }
        };
        ApiTestSuiteService concurrentService = new ApiTestSuiteService(suites, runs, history, users, executor,
                new ApiAssertionService(mapper), new SensitiveDataRedactor(), mapper, mock(PerformanceRegressionService.class), heldExecutor);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        var callers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Boolean>> outcomes = new java.util.ArrayList<>();
            for (int index = 0; index < 4; index++) outcomes.add(callers.submit(() -> {
                ready.countDown(); start.await();
                try { concurrentService.run(8L, new RunRequest(java.util.Map.of(), List.of())); return true; }
                catch (ResponseStatusException error) { assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatusCode()); return false; }
            }));
            ready.await(); start.countDown();
            long accepted = 0;
            for (var outcome : outcomes) if (outcome.get()) accepted++;
            assertEquals(3, accepted);
            assertEquals(3, queued.size());
        } finally { callers.shutdownNow(); }
    }

    @Test
    void runningCancellationUsesAConditionalTransitionAndReturnsPersistedState() {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        ApiSuiteRun running = new ApiSuiteRun(); running.setId(90L); running.setUser(owner); running.setSuiteId(8L); running.setSuiteName("suite"); running.setStatus("RUNNING");
        ApiSuiteRun requested = new ApiSuiteRun(); requested.setId(90L); requested.setUser(owner); requested.setSuiteId(8L); requested.setSuiteName("suite"); requested.setStatus("CANCEL_REQUESTED");
        when(runs.findByIdAndUser(90L, owner)).thenReturn(Optional.of(running), Optional.of(requested));
        when(runs.requestRunningCancellation(90L)).thenReturn(1);

        RunView result = service.cancelRun(90L);

        assertEquals("CANCEL_REQUESTED", result.status());
        verify(runs).requestRunningCancellation(90L);
        verify(runs, never()).save(argThat(value -> "CANCEL_REQUESTED".equals(value.getStatus())));
    }

    @Test
    void queuedCancellationWinsBeforeAnyWorkerClaim() {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        ApiSuiteRun queued = new ApiSuiteRun(); queued.setId(97L); queued.setUser(owner); queued.setSuiteId(8L); queued.setSuiteName("suite"); queued.setStatus("QUEUED");
        ApiSuiteRun cancelled = new ApiSuiteRun(); cancelled.setId(97L); cancelled.setUser(owner); cancelled.setSuiteId(8L); cancelled.setSuiteName("suite"); cancelled.setStatus("CANCELLED");
        when(runs.findByIdAndUser(97L, owner)).thenReturn(Optional.of(queued), Optional.of(cancelled));
        when(runs.cancelQueued(eq(97L), any())).thenReturn(1);

        RunView result = service.cancelRun(97L);

        assertEquals("CANCELLED", result.status());
        verify(runs, never()).requestRunningCancellation(97L);
    }

    @Test
    void cancellationCompletionRaceReturnsTheTerminalStateWhenCompletionWins() {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        ApiSuiteRun running = new ApiSuiteRun(); running.setId(94L); running.setUser(owner); running.setSuiteId(8L); running.setSuiteName("suite"); running.setStatus("RUNNING");
        ApiSuiteRun passed = new ApiSuiteRun(); passed.setId(94L); passed.setUser(owner); passed.setSuiteId(8L); passed.setSuiteName("suite"); passed.setStatus("PASSED");
        when(runs.findByIdAndUser(94L, owner)).thenReturn(Optional.of(running), Optional.of(passed));
        when(runs.requestRunningCancellation(94L)).thenReturn(0);

        RunView result = service.cancelRun(94L);

        assertEquals("PASSED", result.status());
        verify(runs).requestRunningCancellation(94L);
    }

    @Test
    void runningSuiteCannotBeComparedBeforeItsEvidenceIsFinal() {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        ApiSuiteRun running = new ApiSuiteRun(); running.setId(95L); running.setUser(owner); running.setSuiteId(8L); running.setSuiteName("suite"); running.setStatus("RUNNING");
        when(runs.findByIdAndUser(95L, owner)).thenReturn(Optional.of(running));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.compareRun(95L, 5L));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(performance);
    }

    @Test
    void rejectedQueueMarksPersistedRunErrorInsteadOfLeavingItQueued() throws Exception {
        User owner = new User(); owner.setId(404L); when(users.requireUser()).thenReturn(owner);
        ApiTestSuite suite = suite(owner, "CONTINUE", List.of(testCase("case-1", "request", "GET", "https://api.example.test/", List.of(), List.of(), java.util.Map.of())));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        when(runs.save(any(ApiSuiteRun.class))).thenAnswer(invocation -> { ApiSuiteRun value = invocation.getArgument(0); value.setId(91L); persistedRun.set(value); return value; });
        ThreadPoolTaskExecutor rejectingExecutor = new ThreadPoolTaskExecutor() {
            @Override public Future<?> submit(Runnable task) { throw new java.util.concurrent.RejectedExecutionException("full"); }
        };
        ApiTestSuiteService rejectingService = new ApiTestSuiteService(suites, runs, history, users, executor,
                new ApiAssertionService(mapper), new SensitiveDataRedactor(), mapper, mock(PerformanceRegressionService.class), rejectingExecutor);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> rejectingService.run(8L, new RunRequest(java.util.Map.of(), List.of())));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertEquals("ERROR", persistedRun.get().getStatus());
        assertEquals("Suite execution queue is full; try again later", persistedRun.get().getFailureSummary());
    }

    @Test
    void restartRecoveryMarksActiveRunsInterruptedWithoutChangingCompletedResults() {
        ApiSuiteRun queued = recoveryRun(92L, "QUEUED", "[]");
        ApiSuiteRun running = recoveryRun(93L, "RUNNING", "[{\"caseId\":\"done\",\"status\":\"PASSED\"}]");
        when(runs.findByStatusIn(List.of("QUEUED", "RUNNING", "CANCEL_REQUESTED"))).thenReturn(List.of(queued, running));

        service.recoverInterruptedRuns();

        assertEquals("ERROR", queued.getStatus());
        assertEquals("ERROR", running.getStatus());
        assertEquals("[{\"caseId\":\"done\",\"status\":\"PASSED\"}]", running.getResultsJson());
        assertTrue(running.getFailureSummary().contains("was not replayed"));
        verify(runs, times(2)).save(any(ApiSuiteRun.class));
    }

    @Test
    void definitionsAreRedactedBeforeTheyArePersisted() {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        when(suites.save(any(ApiTestSuite.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new com.Aryan.APIX.model.ApiRequest();
        request.setMethod("POST"); request.setUrl("https://api.example.test/users"); request.setHeaders(java.util.Map.of("Authorization", "Bearer raw-token"));
        request.setBody("{\"password\":\"raw-password\"}");
        var testCase = new com.Aryan.APIX.model.SuiteDtos.SuiteCase(null, "create", request, List.of(), java.util.Map.of(), List.of(), 1000L);
        var created = service.create(new com.Aryan.APIX.model.SuiteDtos.SuiteUpsert("safe suite", "", List.of(testCase), "CONTINUE"));

        assertEquals("[REDACTED]", created.cases().getFirst().request().getHeaders().get("Authorization"));
        assertTrue(created.cases().getFirst().request().getBody().contains("[REDACTED]"));
        verify(suites).save(argThat(suite -> suite.getUser() == owner));
    }

    @Test
    void malformedCaseIdentifiersAreRejected() {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        var request = new ApiRequest(); request.setMethod("GET"); request.setUrl("https://api.example.test/");
        var invalid = new SuiteCase("case/../../other", "bad id", request, List.of(), java.util.Map.of(), List.of(), 1000L);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.create(new SuiteUpsert("suite", "", List.of(invalid), "CONTINUE")));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(suites, never()).save(any());
    }

    @Test
    void runtimeValuesAreRedactedFromErrorsEvenWhenNotMarkedSecret() throws Exception {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        ApiTestSuite suite = suite(owner, "CONTINUE", List.of(testCase("case-1", "request", "GET",
                "https://api.example.test/{{value}}", List.of(), List.of(), java.util.Map.of())));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        stubRunPersistence(20L, owner);
        when(executor.execute(any(ApiRequest.class))).thenThrow(new IllegalStateException("connection failed with echo-value"));

        service.run(8L, new RunRequest(java.util.Map.of("value", "echo-value"), List.of()));
        RunView result = service.getRun(20L);

        assertEquals("ERROR", result.results().getFirst().status());
        assertFalse(result.results().getFirst().error().contains("echo-value"));
        assertFalse(result.failureSummary().contains("echo-value"));
        verify(executor).execute(argThat(request -> request.getUrl().endsWith("/echo-value")));
    }

    @Test
    void oversizedExtractedVariablesFailTheCaseWithoutPersistingTheValue() throws Exception {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        ApiTestSuite suite = suite(owner, "CONTINUE", List.of(testCase("case-1", "extract", "GET",
                "https://api.example.test/", List.of(), List.of(new AssertionSpec("STATUS_EQUALS", null, 200)),
                java.util.Map.of("largeValue", new ExtractionSpec("$.value", true)))));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        stubRunPersistence(21L, owner);
        String oversized = "x".repeat(4097);
        when(executor.execute(any(ApiRequest.class))).thenReturn(execution(200, "{\"value\":\"" + oversized + "\"}"));

        service.run(8L, new RunRequest(java.util.Map.of(), List.of()));
        RunView result = service.getRun(21L);

        assertEquals("ERROR", result.results().getFirst().status());
        assertNull(result.results().getFirst().responseBody());
        assertEquals("Case execution failed; sensitive diagnostic details were suppressed", result.results().getFirst().error());
    }

    @Test
    void dependentStepsUseExtractedValuesAndMaskSecretEvidence() throws Exception {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        ApiTestSuite suite = suite(owner, "CONTINUE", List.of(
                testCase("create", "create", "POST", "https://api.example.test/users", List.of(),
                        List.of(new AssertionSpec("STATUS_EQUALS", null, 201)), java.util.Map.of("authToken", new ExtractionSpec("$.value", true))),
                testCase("use-token", "fetch with token", "GET", "https://api.example.test/users/{{authToken}}", List.of("create"),
                        List.of(new AssertionSpec("STATUS_EQUALS", null, 200)), java.util.Map.of()),
                testCase("after", "independent check", "GET", "https://api.example.test/health", List.of(),
                        List.of(new AssertionSpec("STATUS_EQUALS", null, 200)), java.util.Map.of())));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        stubRunPersistence(12L, owner);
        when(executor.execute(any(ApiRequest.class))).thenAnswer(invocation -> {
            ApiRequest request = invocation.getArgument(0);
            return execution(request.getUrl().endsWith("/users") ? 201 : 200, request.getUrl().endsWith("/users") ? "{\"value\":\"sensitive-42\"}" : "{}");
        });

        service.run(8L, new RunRequest(java.util.Map.of(), List.of()));
        RunView result = service.getRun(12L);

        assertEquals("PASSED", result.status());
        assertEquals(3, result.passed());
        assertTrue(result.results().get(0).responseBody().contains("[REDACTED]"));
        verify(executor, times(3)).execute(any(ApiRequest.class));
        verify(executor).execute(argThat(request -> request.getUrl().endsWith("/users/sensitive-42") && request.getSensitiveValues().contains("sensitive-42")));
    }

    @Test
    void failedDependencyIsSkippedWhileIndependentWorkContinues() throws Exception {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        ApiTestSuite suite = suite(owner, "CONTINUE", List.of(
                testCase("first", "expected failure", "GET", "https://api.example.test/a", List.of(), List.of(new AssertionSpec("STATUS_EQUALS", null, 200)), java.util.Map.of()),
                testCase("dependent", "requires first", "GET", "https://api.example.test/{{missing}}", List.of("first"), List.of(new AssertionSpec("REQUEST_COMPLETED", null, null)), java.util.Map.of()),
                testCase("independent", "independent", "GET", "https://api.example.test/health", List.of(), List.of(new AssertionSpec("STATUS_EQUALS", null, 200)), java.util.Map.of())));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        stubRunPersistence(13L, owner);
        when(executor.execute(any(ApiRequest.class))).thenAnswer(invocation -> {
            ApiRequest request = invocation.getArgument(0);
            return request.getUrl().endsWith("/health") ? execution(200, "ok") : execution(503, "unavailable");
        });

        service.run(8L, new RunRequest(java.util.Map.of(), List.of()));
        RunView result = service.getRun(13L);

        assertEquals(1, result.failed()); assertEquals(1, result.skipped());
        assertEquals("SKIPPED", result.results().get(1).status());
        assertEquals("PARTIAL", result.status());
        verify(executor, times(2)).execute(any(ApiRequest.class));
    }

    @Test
    void stopOnFailureSkipsRemainingCases() throws Exception {
        User owner = new User(); when(users.requireUser()).thenReturn(owner);
        ApiTestSuite suite = suite(owner, "STOP_ON_FAILURE", List.of(
                testCase("first", "fails", "GET", "https://api.example.test/a", List.of(), List.of(new AssertionSpec("STATUS_EQUALS", null, 200)), java.util.Map.of()),
                testCase("second", "not executed", "GET", "https://api.example.test/b", List.of(), List.of(new AssertionSpec("REQUEST_COMPLETED", null, null)), java.util.Map.of())));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        stubRunPersistence(14L, owner);
        when(executor.execute(any(ApiRequest.class))).thenReturn(execution(500, "failed"));

        service.run(8L, new RunRequest(java.util.Map.of(), List.of()));
        RunView result = service.getRun(14L);

        assertEquals("PARTIAL", result.status()); assertEquals("SKIPPED", result.results().get(1).status());
        verify(executor, times(1)).execute(any(ApiRequest.class));
    }

    @Test
    void websocketScenarioSendsOrderedFramesEvaluatesAssertionsAndRedactsEvidence() throws Exception {
        User owner = new User(); owner.setId(501L); when(users.requireUser()).thenReturn(owner);
        WebSocketWorkbenchService webSockets = mock(WebSocketWorkbenchService.class);
        var websocket = new WebSocketScenario("wss://api.example.test/socket/{{room}}",
                java.util.Map.of("Authorization", "{{token}}"), 4000L, List.of(
                new WebSocketStep("SEND_JSON", "{\"token\":\"{{token}}\"}", null, List.of()),
                new WebSocketStep("RECEIVE", null, 4000L, List.of(new AssertionSpec("MESSAGE_EQUALS", null, "{{token}}")))));
        SuiteCase wsCase = new SuiteCase("socket", "socket scenario", null, List.of(), java.util.Map.of(), List.of(),
                12000L, "WEBSOCKET", websocket);
        ApiTestSuite suite = suite(owner, "CONTINUE", List.of(wsCase));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        stubRunPersistence(97L, owner);
        when(webSockets.connectForOwner(eq(501L), any(), anyList())).thenReturn(
                new SessionView("session-97", "CONNECTING", "wss://api.example.test/socket/[REDACTED]", 0, java.util.Map.of(), null, null, List.of(), null));
        doNothing().when(webSockets).awaitConnectedForOwner(eq("session-97"), eq(501L), anyLong());
        when(webSockets.getForOwner("session-97", 501L)).thenReturn(
                new SessionView("session-97", "CONNECTED", "wss://api.example.test/socket/[REDACTED]", 101,
                        java.util.Map.of("X-Echo", "run-secret", "Set-Cookie", "cookie"), LocalDateTime.now(), null, List.of(), null));
        when(webSockets.awaitMessageForOwner(eq("session-97"), eq(501L), eq(0L), anyLong()))
                .thenReturn(new MessageView(1, LocalDateTime.now(), "TEXT", "run-secret"));
        ApiTestSuiteService websocketService = new ApiTestSuiteService(suites, runs, history, users, executor,
                new ApiAssertionService(mapper), new SensitiveDataRedactor(), mapper, performance, runExecutor, webSockets);

        websocketService.run(8L, new RunRequest(java.util.Map.of("token", "run-secret", "room", "users"), List.of()));

        verify(webSockets).connectForOwner(eq(501L), argThat(request -> request.url().equals("wss://api.example.test/socket/users")
                && request.headers().get("Authorization").equals("run-secret")), anyList());
        verify(webSockets).sendForOwner("session-97", 501L, "{\"token\":\"run-secret\"}");
        verify(webSockets).closeForOwner("session-97", 501L);
        verify(executor, never()).execute(any(ApiRequest.class));
        RunView result = websocketService.getRun(97L);
        assertEquals("PASSED", result.status());
        assertEquals(1, result.completedCases());
        assertEquals("WEBSOCKET", result.results().getFirst().protocol());
        assertEquals("[REDACTED]", result.results().getFirst().webSocketMessages().getFirst().payload());
        assertTrue(result.results().getFirst().assertions().getFirst().passed());
        assertEquals("[REDACTED]", result.results().getFirst().assertions().getFirst().actual());
        assertEquals("[REDACTED]", result.results().getFirst().responseHeaders().get("X-Echo"));
        assertFalse(mapper.writeValueAsString(result).contains("run-secret"));
    }

    @Test
    void websocketReceiveTimeoutProducesErrorProgressAndClosesSession() throws Exception {
        User owner = new User(); owner.setId(502L); when(users.requireUser()).thenReturn(owner);
        WebSocketWorkbenchService webSockets = mock(WebSocketWorkbenchService.class);
        SuiteCase wsCase = new SuiteCase("socket", "socket timeout", null, List.of(), java.util.Map.of(), List.of(),
                2000L, "WEBSOCKET", new WebSocketScenario("wss://api.example.test/socket", java.util.Map.of(), 1000L,
                List.of(new WebSocketStep("RECEIVE", null, 100L, List.of(new AssertionSpec("MESSAGE_CONTAINS", null, "pong"))))));
        ApiTestSuite suite = suite(owner, "CONTINUE", List.of(wsCase));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        stubRunPersistence(98L, owner);
        when(webSockets.connectForOwner(eq(502L), any(), anyList())).thenReturn(
                new SessionView("session-98", "CONNECTING", "wss://api.example.test/socket", 0, java.util.Map.of(), null, null, List.of(), null));
        doNothing().when(webSockets).awaitConnectedForOwner(eq("session-98"), eq(502L), anyLong());
        when(webSockets.getForOwner("session-98", 502L)).thenReturn(
                new SessionView("session-98", "CONNECTED", "wss://api.example.test/socket", 101, java.util.Map.of(), LocalDateTime.now(), null, List.of(), null));
        when(webSockets.awaitMessageForOwner(eq("session-98"), eq(502L), eq(0L), anyLong())).thenReturn(null);
        ApiTestSuiteService websocketService = new ApiTestSuiteService(suites, runs, history, users, executor,
                new ApiAssertionService(mapper), new SensitiveDataRedactor(), mapper, performance, runExecutor, webSockets);

        websocketService.run(8L, new RunRequest(java.util.Map.of(), List.of()));

        RunView result = websocketService.getRun(98L);
        assertEquals("ERROR", result.status());
        assertEquals("ERROR", result.results().getFirst().status());
        assertEquals(1, result.completedCases());
        assertTrue(result.results().getFirst().error().contains("receive timed out"));
        verify(webSockets).closeForOwner("session-98", 502L);
    }

    @Test
    void websocketDefinitionsRejectUnsafeOrMalformedSteps() {
        User owner = new User(); owner.setId(503L); when(users.requireUser()).thenReturn(owner);
        SuiteCase invalid = new SuiteCase(null, "bad websocket", null, List.of(), java.util.Map.of(), List.of(), 1000L,
                "WEBSOCKET", new WebSocketScenario("http://api.example.test/socket", java.util.Map.of(), null,
                List.of(new WebSocketStep("EXECUTE", "anything", null, List.of()))));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.create(new SuiteUpsert("invalid websocket", "", List.of(invalid), "CONTINUE")));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(suites, never()).save(any(ApiTestSuite.class));
    }

    @Test
    void cancellingDuringWebSocketReceiveClosesConnectionAndPreservesCompletedProgress() throws Exception {
        User owner = new User(); owner.setId(504L); when(users.requireUser()).thenReturn(owner);
        WebSocketWorkbenchService webSockets = mock(WebSocketWorkbenchService.class);
        SuiteCase wsCase = new SuiteCase("socket", "wait for server", null, List.of(), java.util.Map.of(), List.of(),
                20_000L, "WEBSOCKET", new WebSocketScenario("wss://api.example.test/socket", java.util.Map.of(), 5000L,
                List.of(new WebSocketStep("RECEIVE", null, 15_000L, List.of()))));
        ApiTestSuite suite = suite(owner, "CONTINUE", List.of(wsCase));
        when(suites.findByIdAndUser(8L, owner)).thenReturn(Optional.of(suite));
        when(runs.save(any(ApiSuiteRun.class))).thenAnswer(invocation -> {
            ApiSuiteRun saved = invocation.getArgument(0); saved.setId(99L); persistedRun.set(saved); return saved;
        });
        when(runs.claimQueued(eq(99L), any())).thenAnswer(invocation -> { persistedRun.get().setStatus("RUNNING"); return 1; });
        when(runs.findById(99L)).thenAnswer(invocation -> Optional.ofNullable(persistedRun.get()));
        when(runs.findByIdAndUser(99L, owner)).thenAnswer(invocation -> Optional.ofNullable(persistedRun.get()));
        when(runs.requestRunningCancellation(99L)).thenAnswer(invocation -> { persistedRun.get().setStatus("CANCEL_REQUESTED"); return 1; });
        when(runs.cancelQueued(eq(99L), any())).thenReturn(0);
        when(webSockets.connectForOwner(eq(504L), any(), anyList())).thenReturn(
                new SessionView("session-99", "CONNECTING", "wss://api.example.test/socket", 0, java.util.Map.of(), null, null, List.of(), null));
        doNothing().when(webSockets).awaitConnectedForOwner(eq("session-99"), eq(504L), anyLong());
        when(webSockets.getForOwner("session-99", 504L)).thenReturn(
                new SessionView("session-99", "CONNECTED", "wss://api.example.test/socket", 101, java.util.Map.of(), LocalDateTime.now(), null, List.of(), null));
        CountDownLatch receiveStarted = new CountDownLatch(1);
        when(webSockets.awaitMessageForOwner(eq("session-99"), eq(504L), eq(0L), anyLong())).thenAnswer(invocation -> {
            receiveStarted.countDown();
            new CountDownLatch(1).await();
            return null;
        });
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        ThreadPoolTaskExecutor heldExecutor = new ThreadPoolTaskExecutor() {
            @Override public Future<?> submit(Runnable task) { queuedTask.set(task); return java.util.concurrent.CompletableFuture.completedFuture(null); }
        };
        ApiTestSuiteService websocketService = new ApiTestSuiteService(suites, runs, history, users, executor,
                new ApiAssertionService(mapper), new SensitiveDataRedactor(), mapper, performance, heldExecutor, webSockets);

        websocketService.run(8L, new RunRequest(java.util.Map.of(), List.of()));
        Thread worker = new Thread(queuedTask.get()); worker.start();
        assertTrue(receiveStarted.await(2, TimeUnit.SECONDS));
        var cancellation = websocketService.cancelRun(99L);
        worker.join(2000);

        assertFalse(worker.isAlive());
        assertEquals("CANCEL_REQUESTED", cancellation.status());
        RunView finalRun = websocketService.getRun(99L);
        assertEquals("CANCELLED", finalRun.status());
        assertEquals(0, finalRun.completedCases());
        assertEquals(0, finalRun.results().size());
        verify(webSockets).closeForOwner("session-99", 504L);
    }

    private ApiTestSuite suite(User owner, String policy, List<SuiteCase> cases) throws Exception {
        ApiTestSuite suite = new ApiTestSuite(); suite.setId(8L); suite.setUser(owner); suite.setName("test suite"); suite.setFailurePolicy(policy);
        suite.setCasesJson(mapper.writeValueAsString(cases)); return suite;
    }

    private void stubRunPersistence(long id, User owner) {
        when(runs.save(any(ApiSuiteRun.class))).thenAnswer(invocation -> {
            ApiSuiteRun run = invocation.getArgument(0);
            if (run.getId() == null) run.setId(id);
            persistedRun.set(run);
            return run;
        });
        when(runs.findByIdAndUser(id, owner)).thenAnswer(invocation -> Optional.ofNullable(persistedRun.get()));
    }

    private ApiSuiteRun recoveryRun(long id, String status, String results) {
        ApiSuiteRun run = new ApiSuiteRun(); run.setId(id); run.setStatus(status); run.setSuiteId(8L); run.setSuiteName("suite");
        run.setResultsJson(results); run.setStartedAt(java.time.LocalDateTime.now()); run.setCompletedCases(1); return run;
    }


    private SuiteCase testCase(String id, String name, String method, String url, List<String> dependencies,
                               List<AssertionSpec> assertions, java.util.Map<String, ExtractionSpec> extract) {
        ApiRequest request = new ApiRequest(); request.setMethod(method); request.setUrl(url); request.setHeaders(java.util.Map.of());
        return new SuiteCase(id, name, request, assertions, extract, dependencies, 1000L);
    }

    private ApiExecutionResponse execution(int status, String body) {
        ApiResponse response = new ApiResponse(); response.setStatusCode(status); response.setBody(body); response.setHeaders(java.util.Map.of()); response.setResponseTime(5);
        ApiExecutionResponse execution = new ApiExecutionResponse(); execution.setApiResponse(response); execution.setExecutionId(java.util.UUID.randomUUID().toString()); execution.setExecutionError(false); return execution;
    }
}
