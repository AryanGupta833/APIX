package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.ApiSuiteRun;
import com.Aryan.APIX.entity.ApiTestSuite;
import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.entity.User;
import com.Aryan.APIX.model.ApiExecutionResponse;
import com.Aryan.APIX.model.ApiRequest;
import com.Aryan.APIX.model.ApiResponse;
import com.Aryan.APIX.model.PerformanceDtos.RegressionReport;
import com.Aryan.APIX.model.WebSocketDtos.ConnectRequest;
import com.Aryan.APIX.model.WebSocketDtos.MessageView;
import com.Aryan.APIX.model.SuiteDtos.*;
import com.Aryan.APIX.repository.ApiSuiteRunRepository;
import com.Aryan.APIX.repository.ApiTestSuiteRepository;
import com.Aryan.APIX.repository.RequestHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ApiTestSuiteService {
    private static final int MAX_CASES = 25;
    private static final int MAX_ASSERTIONS = 50;
    private static final long MAX_CASE_TIMEOUT_MS = 30_000;
    private static final long MAX_RUN_MS = 600_000;
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_.-]{0,63})}}" );
    private static final Pattern SENSITIVE_NAME = Pattern.compile("(?i).*(authorization|cookie|token|secret|password|credential|api[-_]?key).*" );
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> FAILURE_POLICIES = Set.of("CONTINUE", "STOP_ON_FAILURE");

    private final ApiTestSuiteRepository suites;
    private final ApiSuiteRunRepository runs;
    private final RequestHistoryRepository history;
    private final CurrentUserService users;
    private final RequestExecutionService executor;
    private final ApiAssertionService assertions;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper mapper;
    private final PerformanceRegressionService performance;
    private final ThreadPoolTaskExecutor suiteRunExecutor;
    private final WebSocketWorkbenchService webSockets;
    private final ConcurrentHashMap<Long, RunControl> activeRuns = new ConcurrentHashMap<>();
    private final Object[] userAdmissionLocks = new Object[64];
    private static final List<String> ACTIVE_STATUSES = List.of("QUEUED", "RUNNING", "CANCEL_REQUESTED");
    private static final int MAX_ACTIVE_PER_USER = 3;

    @Autowired
    public ApiTestSuiteService(ApiTestSuiteRepository suites, ApiSuiteRunRepository runs, RequestHistoryRepository history,
                               CurrentUserService users, RequestExecutionService executor, ApiAssertionService assertions,
                               SensitiveDataRedactor redactor, ObjectMapper mapper, PerformanceRegressionService performance,
                               @Qualifier("suiteRunExecutor") ThreadPoolTaskExecutor suiteRunExecutor,
                               WebSocketWorkbenchService webSockets) {
        this.suites = suites; this.runs = runs; this.history = history; this.users = users; this.executor = executor;
        this.assertions = assertions; this.redactor = redactor; this.mapper = mapper; this.performance = performance;
        this.suiteRunExecutor = suiteRunExecutor; this.webSockets = webSockets;
        Arrays.setAll(userAdmissionLocks, ignored -> new Object());
    }

    public ApiTestSuiteService(ApiTestSuiteRepository suites, ApiSuiteRunRepository runs, RequestHistoryRepository history,
                               CurrentUserService users, RequestExecutionService executor, ApiAssertionService assertions,
                               SensitiveDataRedactor redactor, ObjectMapper mapper, PerformanceRegressionService performance,
                               @Qualifier("suiteRunExecutor") ThreadPoolTaskExecutor suiteRunExecutor) {
        this(suites, runs, history, users, executor, assertions, redactor, mapper, performance, suiteRunExecutor, null);
    }

    @PostConstruct
    @Transactional
    public void recoverInterruptedRuns() {
        LocalDateTime now = LocalDateTime.now();
        for (ApiSuiteRun run : runs.findByStatusIn(ACTIVE_STATUSES)) {
            String previousStatus = run.getStatus();
            run.setStatus("ERROR"); run.setCompletedAt(now);
            if (run.getStartedAt() == null) run.setDurationMs(0L);
            else run.setDurationMs(Math.max(0, java.time.Duration.between(run.getStartedAt(), now).toMillis()));
            run.setCurrentCaseId(null); run.setCurrentCaseName(null);
            run.setFailureSummary("QUEUED".equals(previousStatus)
                    ? "Queued run was not resumed after application restart because runtime variables were not persisted; no outbound request was replayed."
                    : "Run interrupted by application restart; completed case results are preserved. An in-flight request may have reached its target and was not replayed.");
            runs.save(run);
        }
    }

    public PageEnvelope<SuiteView> list(int page, int size) {
        validatePage(page, size);
        Page<ApiTestSuite> result = suites.findByUserOrderByUpdatedAtDesc(users.requireUser(), PageRequest.of(page, size));
        return envelope(result.map(this::view));
    }

    public SuiteView get(long id) { return view(findSuite(id, users.requireUser())); }

    public SuiteView create(SuiteUpsert request) {
        validateName(request == null ? null : request.name());
        User user = users.requireUser();
        List<SuiteCase> cases = normalizeCases(request.cases());
        validateCases(cases);
        ApiTestSuite suite = new ApiTestSuite();
        suite.setUser(user); suite.setName(request.name().trim()); suite.setDescription(cleanDescription(request.description()));
        suite.setFailurePolicy(policy(request.failurePolicy())); suite.setCasesJson(write(cases));
        return view(suites.save(suite));
    }

    public SuiteView update(long id, SuiteUpsert request) {
        validateName(request == null ? null : request.name());
        ApiTestSuite suite = findSuite(id, users.requireUser());
        List<SuiteCase> cases = normalizeCases(request.cases()); validateCases(cases);
        suite.setName(request.name().trim()); suite.setDescription(cleanDescription(request.description()));
        suite.setFailurePolicy(policy(request.failurePolicy())); suite.setCasesJson(write(cases));
        return view(suites.save(suite));
    }

    public void delete(long id) { suites.delete(findSuite(id, users.requireUser())); }

    public SuiteView cloneSuite(long id) {
        ApiTestSuite original = findSuite(id, users.requireUser());
        String copyName = original.getName().substring(0, Math.min(original.getName().length(), 115)) + " copy";
        SuiteUpsert copy = new SuiteUpsert(copyName, original.getDescription(), readCases(original), original.getFailurePolicy());
        return create(copy);
    }

    public SuiteView addCase(long id, SuiteCase testCase) {
        ApiTestSuite suite = findSuite(id, users.requireUser());
        List<SuiteCase> cases = new ArrayList<>(readCases(suite)); cases.add(testCase);
        return replaceCases(suite, cases);
    }

    public SuiteView updateCase(long id, String caseId, SuiteCase testCase) {
        ApiTestSuite suite = findSuite(id, users.requireUser());
        List<SuiteCase> cases = new ArrayList<>(readCases(suite));
        int index = indexOf(cases, caseId); if (index < 0) throw notFound("Test case not found");
        cases.set(index, new SuiteCase(caseId, testCase.name(), testCase.request(), testCase.assertions(), testCase.extract(), testCase.dependsOn(), testCase.timeoutMs(), testCase.protocol(), testCase.webSocket()));
        return replaceCases(suite, cases);
    }

    public SuiteView removeCase(long id, String caseId) {
        ApiTestSuite suite = findSuite(id, users.requireUser());
        List<SuiteCase> cases = new ArrayList<>(readCases(suite));
        if (indexOf(cases, caseId) < 0) throw notFound("Test case not found");
        cases.removeIf(c -> c.id().equals(caseId));
        return replaceCases(suite, cases);
    }

    public SuiteView reorder(long id, CaseOrder order) {
        ApiTestSuite suite = findSuite(id, users.requireUser());
        List<SuiteCase> cases = readCases(suite);
        if (order == null || order.caseIds() == null || order.caseIds().size() != cases.size()) throw badRequest("Provide every case ID exactly once");
        Map<String, SuiteCase> byId = new HashMap<>(); cases.forEach(c -> byId.put(c.id(), c));
        List<SuiteCase> reordered = new ArrayList<>();
        for (String caseId : order.caseIds()) {
            SuiteCase item = byId.remove(caseId); if (item == null) throw badRequest("Case IDs must be unique and belong to this suite");
            reordered.add(item);
        }
        return replaceCases(suite, reordered);
    }

    public RunView run(long id, RunRequest request) {
        User user = users.requireUser(); ApiTestSuite suite = findSuite(id, user); List<SuiteCase> cases = readCases(suite);
        Map<String, String> variables = validateVariables(request == null ? null : request.variables());
        List<String> secretInput = request == null || request.secretVariables() == null ? List.of() : request.secretVariables();
        Set<String> secretNames = new HashSet<>();
        for (String name : secretInput) {
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}") || !secretNames.add(name)) throw badRequest("Secret variable names must be valid and unique");
        }
        for (String name : secretNames) if (!variables.containsKey(name)) throw badRequest("Secret variable is not defined: " + name);
        // Runtime values may contain credentials even when the caller did not flag them.
        List<String> secrets = new ArrayList<>(new LinkedHashSet<>(variables.values()));
        if (cases.isEmpty()) throw badRequest("Cannot run an empty suite");

        Long userId = user.getId();
        int userHash = userId == null ? System.identityHashCode(user) : userId.hashCode();
        Object admissionLock = userAdmissionLocks[Math.floorMod(userHash, userAdmissionLocks.length)];
        ApiSuiteRun run;
        synchronized (admissionLock) {
            if (runs.countByUserAndStatusIn(user, ACTIVE_STATUSES) >= MAX_ACTIVE_PER_USER)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many active suite runs for this user");
            run = new ApiSuiteRun(); run.setUser(user); run.setSuiteId(suite.getId()); run.setSuiteName(suite.getName());
            run.setStatus("QUEUED"); run.setTotalCases(cases.size()); run.setStartedAt(LocalDateTime.now()); run = runs.save(run);
        }
        RunControl control = new RunControl();
        RunView queuedView = runView(run);
        activeRuns.put(run.getId(), control);
        try {
            ApiSuiteRun queued = run;
            Future<?> future = suiteRunExecutor.submit(() -> executeRun(queued, suite, cases, variables, secrets, control, userId));
            control.future = future;
        } catch (RejectedExecutionException e) {
            activeRuns.remove(run.getId(), control);
            run.setStatus("ERROR"); run.setCompletedAt(LocalDateTime.now()); run.setDurationMs(0L);
            run.setFailureSummary("Suite execution queue is full; try again later");
            runs.save(run);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Suite execution queue is full; try again later");
        }
        return queuedView;
    }

    private void executeRun(ApiSuiteRun run, ApiTestSuite suite, List<SuiteCase> cases, Map<String, String> variables,
                            List<String> secrets, RunControl control, Long ownerId) {
        Long runId = run.getId();
        if (control.cancelRequested.get()) { activeRuns.remove(runId, control); return; }
        long startedNanos = System.nanoTime();
        if (runs.claimQueued(runId, LocalDateTime.now()) == 0) { activeRuns.remove(runId, control); return; }
        control.worker = Thread.currentThread();
        List<CaseResult> results = new ArrayList<>(); Map<String, String> caseStatuses = new HashMap<>();
        Map<String, String> runtimeVariables = new HashMap<>(variables);
        boolean stop = false;
        try {
            for (SuiteCase testCase : cases) {
                if (control.cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                    try { finalizeCancelled(run, results, startedNanos); }
                    finally { activeRuns.remove(runId, control); }
                    return;
                }
                LocalDateTime caseStartedAt = LocalDateTime.now();
                run.setCurrentCaseId(testCase.id()); run.setCurrentCaseName(testCase.name());
                persistCurrentCase(run);
                if (System.nanoTime() - startedNanos > MAX_RUN_MS * 1_000_000L) {
                    results.add(withCaseTimes(skipped(testCase, "Suite execution time limit reached"), caseStartedAt)); caseStatuses.put(testCase.id(), "SKIPPED");
                    persistProgress(run, results); continue;
                }
                String dependencyError = dependencyError(testCase, caseStatuses, runtimeVariables);
                if (stop || dependencyError != null) {
                    CaseResult skipped = skipped(testCase, stop ? "Stopped after the first failure" : dependencyError);
                    results.add(withCaseTimes(skipped, caseStartedAt)); caseStatuses.put(testCase.id(), "SKIPPED"); persistProgress(run, results); continue;
                }
                CaseResult result;
                try {
                    if ("WEBSOCKET".equals(testCase.protocol())) {
                        result = executeWebSocketCase(testCase, runtimeVariables, secrets, ownerId, control);
                        if (!"PASSED".equals(result.status()) && suite.getFailurePolicy().equals("STOP_ON_FAILURE")) stop = true;
                    } else {
                    ApiRequest req = expand(testCase, runtimeVariables, secrets);
                    if (control.cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                        try { finalizeCancelled(run, results, startedNanos); }
                        finally { activeRuns.remove(runId, control); }
                        return;
                    }
                    ApiExecutionResponse execution = req.getAgentId() == null ? executor.execute(req) : executor.execute(req, ownerId);
                    List<AssertionResult> assertionResults = assertions.evaluate(resolveAssertions(testCase.assertions(), runtimeVariables), execution);
                    boolean passed = execution != null && !execution.isExecutionError() && assertionResults.stream().allMatch(AssertionResult::passed);
                    assertionResults = redactAssertionResults(assertionResults, secrets);
                    ApiResponse response = execution == null ? null : execution.getApiResponse();
                    String caseStatus = execution == null || execution.isExecutionError() ? "ERROR" : passed ? "PASSED" : "FAILED";
                    result = new CaseResult(testCase.id(), testCase.name(), caseStatus, assertionResults,
                            response == null ? null : response.getStatusCode(), response == null ? null : response.getResponseTime(),
                            execution == null ? null : execution.getExecutionId(), execution == null ? null : execution.getTraceId(),
                            response == null ? Map.of() : redactor.redactHeaders(response.getHeaders(), secrets),
                            response == null ? null : redactor.redactText(response.getBody(), secrets), execution != null && execution.isExecutionError()
                            ? response != null && response.getStatusCode() == 403 && Objects.toString(response.getBody(), "").contains("outbound network policy")
                                ? "Request blocked by APiX outbound network policy" : "Request execution failed or timed out" : null);
                    if (passed) {
                        extract(testCase, response, runtimeVariables, secrets);
                        if (response != null) result = new CaseResult(result.caseId(), result.name(), result.status(), redactAssertionResults(assertionResults, secrets),
                                result.statusCode(), result.responseTimeMs(), result.executionId(), result.traceId(),
                                redactor.redactHeaders(response.getHeaders(), secrets), redactor.redactText(response.getBody(), secrets), result.error());
                    }
                    else if (suite.getFailurePolicy().equals("STOP_ON_FAILURE")) stop = true;
                    }
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted() || control.cancelRequested.get()) {
                        try { finalizeCancelled(run, results, startedNanos); }
                        finally { activeRuns.remove(runId, control); }
                        return;
                    }
                    result = new CaseResult(testCase.id(), testCase.name(), "ERROR", List.of(), null, null, null, null, Map.of(), null,
                            "Case execution failed; sensitive diagnostic details were suppressed");
                    if (suite.getFailurePolicy().equals("STOP_ON_FAILURE")) stop = true;
                }
                result = withCaseTimes(result, caseStartedAt);
                results.add(result); caseStatuses.put(testCase.id(), result.status()); persistProgress(run, results);
            }
        } catch (Exception e) {
            syncLatestRunState(run);
            if (control.cancelRequested.get() || Thread.currentThread().isInterrupted() || "CANCEL_REQUESTED".equals(run.getStatus())) finalizeCancelled(run, results, startedNanos);
            else {
                run.setFailureSummary("Suite execution stopped unexpectedly; completed case results are available");
                run.setStatus("ERROR"); run.setCompletedAt(LocalDateTime.now()); run.setDurationMs((System.nanoTime() - startedNanos) / 1_000_000);
                run.setCurrentCaseId(null); run.setCurrentCaseName(null); tally(run, results); run.setResultsJson(write(results)); run.setCompletedCases(results.size()); runs.save(run);
            }
            activeRuns.remove(runId, control); return;
        }
        syncLatestRunState(run);
        if (control.cancelRequested.get() || Thread.currentThread().isInterrupted() || "CANCEL_REQUESTED".equals(run.getStatus())) { finalizeCancelled(run, results, startedNanos); activeRuns.remove(runId, control); return; }
        run.setCompletedAt(LocalDateTime.now()); run.setDurationMs((System.nanoTime() - startedNanos) / 1_000_000);
        run.setCurrentCaseId(null); run.setCurrentCaseName(null);
        tally(run, results); run.setResultsJson(write(results)); run.setCompletedCases(results.size());
        if (run.getFailed() == 0 && run.getErrored() == 0 && run.getSkipped() == 0) run.setStatus("PASSED");
        else if (run.getPassed() > 0 || run.getSkipped() > 0) run.setStatus("PARTIAL");
        else if (run.getErrored() > 0 && run.getFailed() == 0) run.setStatus("ERROR");
        else run.setStatus("FAILED");
        run.setFailureSummary(results.stream().filter(r -> !r.status().equals("PASSED")).map(r -> r.name() + ": " + r.status()).limit(10).reduce((a,b) -> a + "; " + b).orElse(null));
        try {
            runs.save(run);
        } catch (OptimisticLockingFailureException racedCancellation) {
            syncLatestRunState(run);
            if (!"CANCEL_REQUESTED".equals(run.getStatus())) throw racedCancellation;
            finalizeCancelled(run, results, startedNanos);
        } finally { activeRuns.remove(runId, control); }
    }

    private void finalizeCancelled(ApiSuiteRun run, List<CaseResult> results, long startedNanos) {
        syncLatestRunState(run);
        run.setStatus("CANCELLED"); run.setCompletedAt(LocalDateTime.now());
        run.setDurationMs(Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000));
        run.setCurrentCaseId(null); run.setCurrentCaseName(null);
        run.setFailureSummary("Run cancelled; completed case results are available");
        tally(run, results); run.setResultsJson(write(results)); run.setCompletedCases(results.size()); runs.save(run);
    }

    @Transactional
    public RunView cancelRun(long runId) {
        User user = users.requireUser(); ApiSuiteRun run = runs.findByIdAndUser(runId, user).orElseThrow(() -> notFound("Suite run not found"));
        if (isTerminal(run.getStatus())) return runView(run);
        if (runs.cancelQueued(runId, LocalDateTime.now()) > 0) {
            ApiSuiteRun cancelled = runs.findByIdAndUser(runId, user).orElseThrow(() -> notFound("Suite run not found"));
            RunControl control = activeRuns.remove(runId); if (control != null && control.future != null) control.future.cancel(false);
            return runView(cancelled);
        }
        int requested = runs.requestRunningCancellation(runId);
        ApiSuiteRun latest = runs.findByIdAndUser(runId, user).orElseThrow(() -> notFound("Suite run not found"));
        RunControl control = activeRuns.get(runId);
        if ("CANCEL_REQUESTED".equals(latest.getStatus()) && control != null) {
            control.cancelRequested.set(true);
            if (control.worker != null) control.worker.interrupt();
        }
        return runView(latest);
    }

    private boolean isTerminal(String status) { return Set.of("PASSED", "FAILED", "PARTIAL", "ERROR", "CANCELLED").contains(status); }

    public PageEnvelope<RunView> listRuns(long suiteId, int page, int size) {
        User user = users.requireUser(); findSuite(suiteId, user); validatePage(page, size);
        return envelope(runs.findBySuiteIdAndUserOrderByStartedAtDesc(suiteId, user, PageRequest.of(page, size)).map(this::runView));
    }

    public RunView getRun(long runId) { return runView(runs.findByIdAndUser(runId, users.requireUser()).orElseThrow(() -> notFound("Suite run not found"))); }

    public RegressionReport compareRun(long runId, long baselineId) {
        User user = users.requireUser(); ApiSuiteRun run = runs.findByIdAndUser(runId, user).orElseThrow(() -> notFound("Suite run not found"));
        if (!isTerminal(run.getStatus())) throw badRequest("Wait until the suite run reaches a terminal state before comparing performance");
        List<String> executionIds = runView(run).results().stream().map(CaseResult::executionId).filter(Objects::nonNull).toList();
        if (executionIds.isEmpty()) throw badRequest("This run has no completed API executions to compare");
        List<RequestHistory> samples = history.findByExecutionIdInAndUser(executionIds, user);
        if (samples.size() != executionIds.size()) throw badRequest("Some suite execution history is unavailable");
        return performance.compare(baselineId, new com.Aryan.APIX.model.PerformanceDtos.CreateComparisonRequest(samples.stream().map(RequestHistory::getId).toList()));
    }

    private ApiRequest expand(SuiteCase testCase, Map<String, String> variables, List<String> secrets) {
        ApiRequest source = testCase.request();
        ApiRequest request = new ApiRequest();
        request.setMethod(source.getMethod().toUpperCase(Locale.ROOT));
        request.setAgentId(source.getAgentId());
        String url = substituteUrl(source.getUrl(), variables);
        if (source.getQueryParams() != null && !source.getQueryParams().isEmpty()) {
            StringJoiner query = new StringJoiner("&");
            source.getQueryParams().forEach((key,value) -> query.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(substitute(value, variables), StandardCharsets.UTF_8)));
            url += (url.contains("?") ? "&" : "?") + query;
        }
        request.setUrl(url); request.setBody(substitute(source.getBody(), variables));
        Map<String, String> headers = new LinkedHashMap<>();
        if (source.getHeaders() != null) source.getHeaders().forEach((key,value) -> headers.put(key, substitute(value, variables)));
        request.setHeaders(headers); request.setDiagnosticMode(false); request.setTimeoutMs(testCase.timeoutMs() == null ? 15_000L : testCase.timeoutMs());
        request.setSuppressAiAnalysis(true); request.setSuppressEnrichment(true); request.setSensitiveValues(secrets);
        return request;
    }

    private void extract(SuiteCase testCase, ApiResponse response, Map<String, String> variables, List<String> secrets) {
        if (testCase.extract() == null || response == null || response.getBody() == null) return;
        testCase.extract().forEach((name, spec) -> {
            Object value = assertions.extractJson(response.getBody(), spec.jsonPath());
            if (value != null && !(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
                String text = Objects.toString(value);
                if (text.length() > 4096) throw new IllegalArgumentException("Extracted variable exceeds the 4 KB limit");
                if (!variables.containsKey(name) && variables.size() >= 50) throw new IllegalArgumentException("Run variable limit reached");
                variables.put(name, text);
                if (spec.secret() && !text.isEmpty()) secrets.add(text);
            }
        });
    }

    private String dependencyError(SuiteCase testCase, Map<String, String> statuses, Map<String, String> variables) {
        for (String dependency : testCase.dependsOn() == null ? List.<String>of() : testCase.dependsOn())
            if (!"PASSED".equals(statuses.get(dependency))) return "Required test case did not pass: " + dependency;
        Matcher matcher = VARIABLE.matcher(allTemplates(testCase));
        while (matcher.find()) if (!variables.containsKey(matcher.group(1))) return "Required variable is unavailable: " + matcher.group(1);
        return null;
    }

    private String allTemplates(SuiteCase testCase) {
        StringBuilder text = new StringBuilder();
        if ("WEBSOCKET".equals(testCase.protocol())) {
            WebSocketScenario scenario = testCase.webSocket();
            text.append(scenario.url());
            if (scenario.headers() != null) scenario.headers().values().forEach(text::append);
            if (scenario.steps() != null) scenario.steps().forEach(step -> {
                text.append(Objects.toString(step.message(), ""));
                if (step.assertions() != null) step.assertions().forEach(assertion -> { if (assertion.expected() instanceof String value) text.append(value); });
            });
        } else {
            text.append(testCase.request().getUrl()).append(' ').append(Objects.toString(testCase.request().getBody(), ""));
            if (testCase.request().getHeaders() != null) testCase.request().getHeaders().values().forEach(text::append);
            if (testCase.request().getQueryParams() != null) testCase.request().getQueryParams().values().forEach(text::append);
            if (testCase.assertions() != null) testCase.assertions().forEach(assertion -> { if (assertion.expected() instanceof String value) text.append(value); });
        }
        return text.toString();
    }

    private List<AssertionSpec> resolveAssertions(List<AssertionSpec> specs, Map<String, String> variables) {
        List<AssertionSpec> resolved = new ArrayList<>();
        for (AssertionSpec spec : specs) {
            Object expected = spec.expected();
            if (expected instanceof String text && hasVariable(text)) expected = substitute(text, variables);
            resolved.add(new AssertionSpec(spec.type(), spec.path(), expected));
        }
        return resolved;
    }

    private List<AssertionResult> redactAssertionResults(List<AssertionResult> results, List<String> secrets) {
        return results.stream().map(result -> new AssertionResult(result.type(), result.path(), result.passed(),
                redactValue(result.expected(), secrets), redactValue(result.actual(), secrets), redactor.redactText(result.message(), secrets))).toList();
    }

    private Object redactValue(Object value, List<String> secrets) {
        if (value instanceof String text) return redactor.redactText(text, secrets);
        if (value instanceof List<?> list) return list.stream().map(item -> redactValue(item, secrets)).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>(); map.forEach((key,item) -> result.put(Objects.toString(key), redactValue(item, secrets))); return result;
        }
        return value;
    }

    private static String substitute(String value, Map<String, String> variables) {
        if (value == null) return null;
        Matcher matcher = VARIABLE.matcher(value); StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String replacement = variables.get(matcher.group(1));
            if (replacement == null) throw new IllegalArgumentException("Variable is not defined: " + matcher.group(1));
            if (replacement.contains("{{") || replacement.contains("}}")) throw new IllegalArgumentException("Recursive variable expansion is forbidden");
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        if (output.indexOf("{{") >= 0 || output.indexOf("}}") >= 0) throw new IllegalArgumentException("Malformed variable template");
        return output.toString();
    }

    private static String substituteUrl(String value, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(value); StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String replacement = variables.get(matcher.group(1));
            if (replacement == null) throw new IllegalArgumentException("Variable is not defined: " + matcher.group(1));
            if (replacement.contains("{{") || replacement.contains("}}")) throw new IllegalArgumentException("Recursive variable expansion is forbidden");
            String encoded = URLEncoder.encode(replacement, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "%2F");
            matcher.appendReplacement(output, Matcher.quoteReplacement(encoded));
        }
        matcher.appendTail(output);
        if (output.indexOf("{{") >= 0 || output.indexOf("}}") >= 0) throw new IllegalArgumentException("Malformed variable template");
        return output.toString();
    }

    private List<SuiteCase> normalizeCases(List<SuiteCase> cases) {
        if (cases == null) return List.of();
        List<SuiteCase> normalized = new ArrayList<>();
        for (SuiteCase c : cases) normalized.add(normalizeCase(c));
        return normalized;
    }

    private SuiteCase normalizeCase(SuiteCase c) {
        if (c == null) throw badRequest("Test case is required");
        String id = c.id() == null || c.id().isBlank() ? UUID.randomUUID().toString() : c.id();
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw badRequest("Case IDs may contain only letters, numbers, underscores, and hyphens (up to 64 characters)");
        String protocol = c.protocol() == null || c.protocol().isBlank() ? "HTTP" : c.protocol().trim().toUpperCase(Locale.ROOT);
        ApiRequest safe = null;
        WebSocketScenario safeWebSocket = null;
        if (protocol.equals("HTTP")) {
            ApiRequest input = c.request(); if (input == null) throw badRequest("Request is required for each HTTP test case");
            Map<String, String> headers = new LinkedHashMap<>();
            if (input.getHeaders() != null) input.getHeaders().forEach((key,value) -> {
                if (key == null) throw badRequest("Header names cannot be null");
                headers.put(key, SENSITIVE_NAME.matcher(key).matches() && !hasVariable(value) ? "[REDACTED]" : value);
            });
            Map<String, String> query = new LinkedHashMap<>();
            if (input.getQueryParams() != null) input.getQueryParams().forEach((key,value) -> {
                if (key == null) throw badRequest("Query parameter names cannot be null");
                query.put(key, SENSITIVE_NAME.matcher(key).matches() && !hasVariable(value) ? "[REDACTED]" : value);
            });
            safe = new ApiRequest();
            safe.setMethod(input.getMethod()); safe.setUrl(redactTemplate(input.getUrl(), true)); safe.setHeaders(headers);
            safe.setBody(redactTemplate(input.getBody(), false)); safe.setQueryParams(query); safe.setDiagnosticMode(false);
            safe.setAgentId(input.getAgentId());
        } else if (protocol.equals("WEBSOCKET")) {
            WebSocketScenario source = c.webSocket();
            if (source == null) throw badRequest("WebSocket scenario is required for each WebSocket test case");
            Map<String, String> headers = new LinkedHashMap<>();
            if (source.headers() != null) source.headers().forEach((key,value) -> {
                if (key == null) throw badRequest("WebSocket header names cannot be null");
                headers.put(key, SENSITIVE_NAME.matcher(key).matches() && !hasVariable(value) ? "[REDACTED]" : value);
            });
            List<WebSocketStep> steps = source.steps() == null ? List.of() : source.steps().stream().map(step -> {
                if (step == null) throw badRequest("WebSocket steps cannot be null");
                List<AssertionSpec> stepAssertions = step.assertions() == null ? List.of() : step.assertions();
                return new WebSocketStep(step.action() == null ? null : step.action().trim().toUpperCase(Locale.ROOT),
                        redactTemplate(step.message(), false), step.timeoutMs(), stepAssertions);
            }).toList();
            safeWebSocket = new WebSocketScenario(redactTemplate(source.url(), true), headers,
                    source.connectTimeoutMs(), steps);
        } else throw badRequest("protocol must be HTTP or WEBSOCKET");
        List<AssertionSpec> assertionList = c.assertions() == null ? List.of() : c.assertions();
        return new SuiteCase(id, c.name() == null ? "" : c.name().trim(), safe, assertionList,
                c.extract() == null ? Map.of() : c.extract(), c.dependsOn() == null ? List.of() : c.dependsOn(), c.timeoutMs(), protocol, safeWebSocket);
    }

    private void validateCases(List<SuiteCase> cases) {
        if (cases.size() > MAX_CASES) throw badRequest("A suite can contain at most " + MAX_CASES + " test cases");
        if (write(cases).length() > 1_000_000) throw badRequest("Suite definition exceeds the 1 MB storage limit");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < cases.size(); index++) {
            SuiteCase c = cases.get(index); if (c.id() == null || ids.contains(c.id())) throw badRequest("Test case IDs must be unique");
            if (c.name().isBlank() || c.name().length() > 120) throw badRequest("Each test case needs a name up to 120 characters");
            if ("HTTP".equals(c.protocol())) {
                ApiRequest request = c.request();
                if (request == null || c.webSocket() != null) throw badRequest("HTTP cases require an HTTP request only");
                if (request.getMethod() == null || !METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) throw badRequest("Unsupported HTTP method for " + c.name());
                try {
                    URI uri = URI.create(VARIABLE.matcher(request.getUrl()).replaceAll("placeholder"));
                    if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT)) || uri.getHost() == null || uri.getUserInfo() != null)
                        throw new IllegalArgumentException();
                } catch (Exception e) { throw badRequest("A valid absolute HTTP or HTTPS URL is required for " + c.name()); }
                if (request.getUrl().length() > 4000 || Objects.toString(request.getBody(), "").length() > 100_000) throw badRequest("Request URL or body exceeds the suite limit");
                if (request.getHeaders() != null && (request.getHeaders().size() > 100 || request.getHeaders().entrySet().stream().anyMatch(h -> h.getKey() == null || h.getKey().length() > 200 || Objects.toString(h.getValue(), "").length() > 8192))) throw badRequest("Request headers exceed the suite limit");
                if (c.assertions().size() > MAX_ASSERTIONS) throw badRequest("Too many assertions in " + c.name());
                for (AssertionSpec assertion : c.assertions()) validateAssertion(assertion);
                if (c.extract().size() > 20) throw badRequest("A test case can extract at most 20 variables");
                c.extract().forEach((name,spec) -> { if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}") || spec == null || spec.jsonPath() == null || spec.jsonPath().length() > 256 || !spec.jsonPath().startsWith("$")) throw badRequest("Invalid variable extraction definition"); });
            } else {
                if (c.request() != null || c.webSocket() == null) throw badRequest("WebSocket cases require a WebSocket scenario only");
                if (!c.assertions().isEmpty() || !c.extract().isEmpty()) throw badRequest("WebSocket assertions belong to receive steps; response extraction is not supported");
                validateWebSocketScenario(c.webSocket(), c.name());
            }
            if (c.timeoutMs() != null && (c.timeoutMs() < 100 || c.timeoutMs() > MAX_CASE_TIMEOUT_MS)) throw badRequest("Case timeout must be between 100 and 30000 ms");
            for (String dependency : c.dependsOn()) if (!ids.contains(dependency)) throw badRequest("Dependencies must refer to an earlier case in execution order");
            ids.add(c.id());
        }
    }

    private void validateWebSocketScenario(WebSocketScenario scenario, String caseName) {
        if (scenario.url() == null || scenario.url().length() > 4000) throw badRequest("A WebSocket URL is required for " + caseName);
        try {
            URI uri = URI.create(VARIABLE.matcher(scenario.url()).replaceAll("placeholder"));
            if (!Set.of("ws", "wss").contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (Exception error) { throw badRequest("A valid absolute ws:// or wss:// URL is required for " + caseName); }
        if (scenario.connectTimeoutMs() != null && (scenario.connectTimeoutMs() < 100 || scenario.connectTimeoutMs() > 10_000))
            throw badRequest("WebSocket connect timeout must be between 100 and 10000 ms");
        if (scenario.headers() != null && (scenario.headers().size() > 50 || scenario.headers().entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null || entry.getKey().length() > 200
                        || !entry.getKey().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                        || entry.getValue().length() > 8192 || entry.getValue().contains("\r") || entry.getValue().contains("\n")
                        || Set.of("host", "connection", "upgrade", "content-length", "transfer-encoding").contains(entry.getKey().toLowerCase(Locale.ROOT))
                        || entry.getKey().toLowerCase(Locale.ROOT).startsWith("sec-websocket-"))))
            throw badRequest("WebSocket headers exceed the suite limit");
        if (scenario.steps() == null || scenario.steps().isEmpty() || scenario.steps().size() > 100)
            throw badRequest("A WebSocket scenario requires between 1 and 100 ordered steps");
        for (WebSocketStep step : scenario.steps()) {
            if (!Set.of("SEND_TEXT", "SEND_JSON", "RECEIVE").contains(Objects.toString(step.action(), "")))
                throw badRequest("WebSocket steps must use SEND_TEXT, SEND_JSON, or RECEIVE");
            if (step.action().startsWith("SEND_")) {
                if (step.message() == null || step.message().getBytes(StandardCharsets.UTF_8).length > 64 * 1024)
                    throw badRequest("WebSocket send messages must be at most 64 KiB");
                if (step.action().equals("SEND_JSON") && !hasVariable(step.message())) {
                    try { if (mapper.readTree(step.message()) == null) throw new IllegalArgumentException(); }
                    catch (Exception invalid) { throw badRequest("SEND_JSON requires a valid JSON message"); }
                }
                if (step.timeoutMs() != null || step.assertions() != null && !step.assertions().isEmpty())
                    throw badRequest("Only receive steps may define a timeout or assertions");
            } else {
                if (step.message() != null) throw badRequest("Receive steps cannot define a send message");
                if (step.timeoutMs() != null && (step.timeoutMs() < 100 || step.timeoutMs() > 30_000))
                    throw badRequest("WebSocket receive timeout must be between 100 and 30000 ms");
                if (step.assertions().size() > MAX_ASSERTIONS) throw badRequest("Too many assertions on a WebSocket receive step");
                for (AssertionSpec assertion : step.assertions()) {
                    if (assertion == null || assertion.type() == null) throw badRequest("WebSocket assertion type is required");
                    String type = assertion.type().toUpperCase(Locale.ROOT);
                    if (!Set.of("MESSAGE_EQUALS", "MESSAGE_CONTAINS", "JSON_EXISTS", "JSON_NOT_EXISTS", "JSON_EQUALS", "JSON_NOT_EQUALS", "JSON_GT", "JSON_GTE", "JSON_LT", "JSON_LTE").contains(type))
                        throw badRequest("Unsupported WebSocket message assertion: " + type);
                    if (type.startsWith("JSON_")) validateAssertion(assertion);
                    if ((type.equals("MESSAGE_EQUALS") || type.equals("MESSAGE_CONTAINS")) && !(assertion.expected() instanceof String))
                        throw badRequest(type + " expects a text value");
                }
            }
        }
    }

    private CaseResult executeWebSocketCase(SuiteCase testCase, Map<String, String> variables,
                                             List<String> secrets, Long ownerId, RunControl control) throws InterruptedException {
        if (webSockets == null) throw new IllegalStateException("WebSocket suite transport is unavailable");
        WebSocketScenario scenario = testCase.webSocket();
        long caseLimitMs = testCase.timeoutMs() == null ? 15_000 : testCase.timeoutMs();
        long deadline = System.nanoTime() + caseLimitMs * 1_000_000L;
        long started = System.nanoTime();
        String sessionId = null;
        List<WebSocketMessageResult> messageResults = new ArrayList<>();
        List<AssertionResult> caseAssertions = new ArrayList<>();
        long sequence = 0;
        String failure = null;
        String status = "PASSED";
        Integer handshakeStatus = null;
        Map<String, String> handshakeHeaders = Map.of();
        try {
            checkWebSocketCancellation(control);
            String target = substituteUrl(scenario.url(), variables);
            Map<String, String> headers = new LinkedHashMap<>();
            if (scenario.headers() != null) scenario.headers().forEach((name, value) -> headers.put(name, substitute(value, variables)));
            var session = webSockets.connectForOwner(ownerId, new ConnectRequest(target, headers), secrets);
            sessionId = session.id();
            long connectLimit = scenario.connectTimeoutMs() == null ? 10_000 : scenario.connectTimeoutMs();
            long remaining = remainingMs(deadline);
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException("case deadline");
            webSockets.awaitConnectedForOwner(sessionId, ownerId, Math.min(connectLimit, remaining));
            var connected = webSockets.getForOwner(sessionId, ownerId);
            handshakeHeaders = redactor.redactHeaders(connected.handshakeHeaders(), secrets);
            if ("CONNECTED".equals(connected.state())) handshakeStatus = connected.handshakeStatus();

            for (int index = 0; index < scenario.steps().size(); index++) {
                checkWebSocketCancellation(control);
                WebSocketStep step = scenario.steps().get(index);
                switch (step.action()) {
                    case "SEND_TEXT" -> webSockets.sendForOwner(sessionId, ownerId, substitute(step.message(), variables));
                    case "SEND_JSON" -> {
                        String json = substitute(step.message(), variables);
                        try {
                            if (mapper.readTree(json) == null) throw new IllegalArgumentException();
                        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid JSON message"); }
                        webSockets.sendForOwner(sessionId, ownerId, json);
                    }
                    case "RECEIVE" -> {
                        long budget = remainingMs(deadline);
                        if (budget <= 0) throw new java.util.concurrent.TimeoutException("case deadline");
                        long receiveLimit = step.timeoutMs() == null ? 5_000 : step.timeoutMs();
                        MessageView received = webSockets.awaitMessageForOwner(sessionId, ownerId, sequence, Math.min(receiveLimit, budget));
                        if (received == null) throw new java.util.concurrent.TimeoutException("message receive");
                        sequence = received.sequence();
                        List<AssertionSpec> resolved = resolveAssertions(step.assertions(), variables);
                        List<AssertionResult> evaluated = assertions.evaluateWebSocketMessage(received.payload(), resolved);
                        evaluated = redactAssertionResults(evaluated, secrets);
                        caseAssertions.addAll(evaluated);
                        messageResults.add(new WebSocketMessageResult(index, redactor.redactText(received.payload(), secrets), evaluated));
                        if (evaluated.stream().anyMatch(result -> !result.passed())) status = "FAILED";
                    }
                    default -> throw new IllegalArgumentException("Unsupported WebSocket step");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (java.util.concurrent.TimeoutException timeout) {
            status = "ERROR";
            failure = "WebSocket connection or receive timed out";
        } catch (Exception error) {
            status = "ERROR";
            failure = error instanceof OutboundPolicyBlockedException
                    ? "WebSocket destination blocked by APiX outbound network policy"
                    : "WebSocket connection or scenario execution failed";
        } finally {
            if (sessionId != null) {
                try { webSockets.closeForOwner(sessionId, ownerId); }
                catch (RuntimeException ignored) { /* The session may already have terminated. */ }
            }
        }
        return new CaseResult(testCase.id(), testCase.name(), status, caseAssertions, handshakeStatus,
                Math.max(0, (System.nanoTime() - started) / 1_000_000), null, null,
                handshakeHeaders, null, failure, null, null, "WEBSOCKET", messageResults);
    }

    private void checkWebSocketCancellation(RunControl control) throws InterruptedException {
        if (control.cancelRequested.get() || Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Suite run cancelled");
        }
    }

    private long remainingMs(long deadlineNanos) {
        return Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    private void validateAssertion(AssertionSpec assertion) {
        if (assertion == null || assertion.type() == null) throw badRequest("Assertion type is required");
        String type = assertion.type().toUpperCase(Locale.ROOT);
        Set<String> supported = Set.of("REQUEST_COMPLETED", "STATUS_EQUALS", "STATUS_IN", "HEADER_PRESENT", "HEADER_EQUALS", "RESPONSE_TIME_LTE", "BODY_PRESENT", "BODY_ABSENT", "JSON_SCHEMA", "JSON_EXISTS", "JSON_NOT_EXISTS", "JSON_EQUALS", "JSON_NOT_EQUALS", "JSON_GT", "JSON_GTE", "JSON_LT", "JSON_LTE");
        if (!supported.contains(type)) throw badRequest("Unsupported assertion type: " + type);
        if (type.equals("JSON_SCHEMA") && !(assertion.expected() instanceof Map<?, ?>)) throw badRequest("JSON_SCHEMA expects a schema object");
        if (type.startsWith("JSON_") && !type.equals("JSON_SCHEMA") && (assertion.path() == null || !assertion.path().startsWith("$"))) throw badRequest("JSONPath assertions must start with $");
        if ((type.equals("STATUS_EQUALS") || type.equals("STATUS_IN") || type.equals("RESPONSE_TIME_LTE") || type.endsWith("_EQUALS") || type.matches("JSON_(?:GT|GTE|LT|LTE)")) && assertion.expected() == null) throw badRequest("Expected value is required for " + type);
        if (type.equals("STATUS_IN") && (!(assertion.expected() instanceof List<?> values) || values.isEmpty() || values.stream().anyMatch(v -> !(v instanceof Number)))) throw badRequest("STATUS_IN expects a nonempty array of status codes");
        if ((type.equals("HEADER_PRESENT") || type.equals("HEADER_EQUALS")) && (assertion.path() == null || assertion.path().isBlank())) throw badRequest("Header assertion requires a header name in path");
    }

    private SuiteView replaceCases(ApiTestSuite suite, List<SuiteCase> cases) { cases = normalizeCases(cases); validateCases(cases); suite.setCasesJson(write(cases)); return view(suites.save(suite)); }
    private int indexOf(List<SuiteCase> cases, String id) { for (int i = 0; i < cases.size(); i++) if (Objects.equals(cases.get(i).id(), id)) return i; return -1; }
    private void persistProgress(ApiSuiteRun run, List<CaseResult> results) { syncLatestRunState(run); tally(run, results); run.setResultsJson(write(results)); run.setCompletedCases(results.size()); runs.save(run); }
    private void persistCurrentCase(ApiSuiteRun run) { syncLatestRunState(run); runs.save(run); }
    private void syncLatestRunState(ApiSuiteRun run) { runs.findById(run.getId()).ifPresent(latest -> { run.setVersion(latest.getVersion()); run.setStartedAt(latest.getStartedAt()); if ("CANCEL_REQUESTED".equals(latest.getStatus())) run.setStatus("CANCEL_REQUESTED"); }); }
    private void tally(ApiSuiteRun run, List<CaseResult> results) { run.setPassed((int)results.stream().filter(r -> r.status().equals("PASSED")).count()); run.setFailed((int)results.stream().filter(r -> r.status().equals("FAILED")).count()); run.setSkipped((int)results.stream().filter(r -> r.status().equals("SKIPPED")).count()); run.setErrored((int)results.stream().filter(r -> r.status().equals("ERROR")).count()); }
    private CaseResult skipped(SuiteCase c, String why) { return new CaseResult(c.id(), c.name(), "SKIPPED", List.of(), null, null, null, null, Map.of(), null, why, null, null, c.protocol(), List.of()); }
    private CaseResult withCaseTimes(CaseResult result, LocalDateTime startedAt) { return new CaseResult(result.caseId(), result.name(), result.status(), result.assertions(), result.statusCode(), result.responseTimeMs(), result.executionId(), result.traceId(), result.responseHeaders(), result.responseBody(), result.error(), startedAt, LocalDateTime.now(), result.protocol(), result.webSocketMessages()); }
    private SuiteView view(ApiTestSuite suite) { return new SuiteView(suite.getId(), suite.getName(), suite.getDescription(), readCases(suite), suite.getFailurePolicy(), suite.getCreatedAt(), suite.getUpdatedAt()); }
    private RunView runView(ApiSuiteRun run) { return new RunView(run.getId(), run.getSuiteId(), run.getSuiteName(), run.getStatus(), run.getPassed(), run.getFailed(), run.getSkipped(), run.getErrored(), run.getStartedAt(), run.getCompletedAt(), run.getDurationMs(), read(run.getResultsJson(), new TypeReference<>() { }), run.getFailureSummary(), run.getTotalCases(), run.getCompletedCases(), run.getCurrentCaseId(), run.getCurrentCaseName()); }
    private List<SuiteCase> readCases(ApiTestSuite suite) { return read(suite.getCasesJson(), new TypeReference<>() { }); }
    private <T> T read(String json, TypeReference<T> type) { try { return mapper.readValue(json, type); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Saved suite data is unreadable"); } }
    private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save suite data"); } }
    private ApiTestSuite findSuite(long id, User user) { return suites.findByIdAndUser(id, user).orElseThrow(() -> notFound("Test suite not found")); }
    private String policy(String value) { String policy = value == null || value.isBlank() ? "CONTINUE" : value.toUpperCase(Locale.ROOT); if (!FAILURE_POLICIES.contains(policy)) throw badRequest("failurePolicy must be CONTINUE or STOP_ON_FAILURE"); return policy; }
    private void validateName(String name) { if (name == null || name.isBlank() || name.length() > 120) throw badRequest("Suite name between 1 and 120 characters is required"); }
    private String cleanDescription(String description) { if (description != null && description.length() > 1000) throw badRequest("Suite description is limited to 1000 characters"); return description == null ? null : redactor.redactText(description); }
    private Map<String,String> validateVariables(Map<String,String> input) { if (input == null) return new HashMap<>(); if (input.size() > 50) throw badRequest("At most 50 runtime variables are allowed"); Map<String,String> values = new HashMap<>(); input.forEach((key,value) -> { if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}") || value == null || value.length() > 4096) throw badRequest("Invalid runtime variable"); values.put(key,value); }); return values; }
    private boolean hasVariable(String value) { return value != null && VARIABLE.matcher(value).find(); }
    private String redactTemplate(String value, boolean url) {
        if (value == null) return null;
        Matcher matcher = VARIABLE.matcher(value); StringBuffer protectedText = new StringBuffer(); List<String> placeholders = new ArrayList<>();
        while (matcher.find()) {
            String marker = "__APIX_SUITE_VAR_" + placeholders.size() + "_MARKER__"; placeholders.add(matcher.group());
            matcher.appendReplacement(protectedText, Matcher.quoteReplacement(marker));
        }
        matcher.appendTail(protectedText);
        String safe = url ? redactor.redactUrl(protectedText.toString()) : redactor.redactText(protectedText.toString());
        for (int i = 0; i < placeholders.size(); i++) safe = safe.replace("__APIX_SUITE_VAR_" + i + "_MARKER__", placeholders.get(i));
        return safe;
    }
    private void validatePage(int page, int size) { if (page < 0 || size < 1 || size > 100) throw badRequest("page must be nonnegative and size must be between 1 and 100"); }
    private <T> PageEnvelope<T> envelope(Page<T> page) { return new PageEnvelope<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }

    private static final class RunControl {
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private volatile Future<?> future;
        private volatile Thread worker;
    }
}
