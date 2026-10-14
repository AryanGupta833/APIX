package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.PerformanceBaseline;
import com.Aryan.APIX.entity.PerformanceRegressionReport;
import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.entity.User;
import com.Aryan.APIX.model.PerformanceDtos.*;
import com.Aryan.APIX.repository.PerformanceBaselineRepository;
import com.Aryan.APIX.repository.PerformanceRegressionReportRepository;
import com.Aryan.APIX.repository.RequestHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

@Service
public class PerformanceRegressionService {
    private static final int MAX_SAMPLES = 500;
    private final PerformanceBaselineRepository baselineRepository;
    private final PerformanceRegressionReportRepository reportRepository;
    private final RequestHistoryRepository historyRepository;
    private final CurrentUserService currentUserService;
    private final PerformanceMetricService metricService;
    private final PerformanceComparisonService comparisonService;
    private final ObjectMapper objectMapper;

    public PerformanceRegressionService(PerformanceBaselineRepository baselineRepository,
                                        PerformanceRegressionReportRepository reportRepository,
                                        RequestHistoryRepository historyRepository,
                                        CurrentUserService currentUserService,
                                        PerformanceMetricService metricService,
                                        PerformanceComparisonService comparisonService,
                                        ObjectMapper objectMapper) {
        this.baselineRepository = baselineRepository;
        this.reportRepository = reportRepository;
        this.historyRepository = historyRepository;
        this.currentUserService = currentUserService;
        this.metricService = metricService;
        this.comparisonService = comparisonService;
        this.objectMapper = objectMapper;
    }

    public PageEnvelope<HistorySample> samples(String method, String url, int page, int size) {
        validatePage(page, size);
        if (method == null || method.isBlank() || url == null || url.isBlank()) {
            throw badRequest("method and url are required to select comparable executions");
        }
        Page<RequestHistory> results = historyRepository.findByUserAndMethodIgnoreCaseAndUrlOrderByCreatedAtDesc(
                currentUserService.requireUser(), method.trim(), url.trim(), PageRequest.of(page, size));
        return envelope(results.map(this::toSample));
    }

    public Baseline createBaseline(CreateBaselineRequest request) {
        if (request == null || request.name() == null || request.name().isBlank() || request.name().length() > 120) {
            throw badRequest("A baseline name between 1 and 120 characters is required");
        }
        User user = currentUserService.requireUser();
        List<RequestHistory> samples = loadSamples(request.sampleIds(), user);
        String signature = scenarioFingerprint(samples.get(0));
        if (samples.stream().anyMatch(sample -> !signature.equals(scenarioFingerprint(sample)))) {
            throw badRequest("Baseline samples must use the same method, URL, request body, and header names");
        }
        MetricsSnapshot metrics = metricService.aggregate(samples);
        requireEnoughSamples(metrics);

        PerformanceBaseline baseline = new PerformanceBaseline();
        baseline.setUser(user);
        baseline.setName(request.name().trim());
        baseline.setMethod(samples.get(0).getMethod());
        baseline.setUrl(samples.get(0).getUrl());
        baseline.setScenarioFingerprint(signature);
        baseline.setSampleCount(metrics.sampleCount());
        baseline.setSampleIdsJson(writeJson(request.sampleIds()));
        baseline.setMetricsJson(writeJson(metrics));
        baseline.setWindowStart(metrics.windowStart());
        baseline.setWindowEnd(metrics.windowEnd());
        return toBaseline(baselineRepository.save(baseline));
    }

    public PageEnvelope<Baseline> listBaselines(int page, int size) {
        validatePage(page, size);
        Page<PerformanceBaseline> results = baselineRepository.findByUserAndActiveTrueOrderByCreatedAtDesc(
                currentUserService.requireUser(), PageRequest.of(page, size));
        return envelope(results.map(this::toBaseline));
    }

    public Baseline getBaseline(long id) {
        User user = currentUserService.requireUser();
        return toBaseline(findBaseline(id, user));
    }

    public void deleteBaseline(long id) {
        User user = currentUserService.requireUser();
        PerformanceBaseline baseline = findBaseline(id, user);
        baseline.setActive(false);
        baselineRepository.save(baseline);
    }

    public RegressionReport compare(long baselineId, CreateComparisonRequest request) {
        User user = currentUserService.requireUser();
        PerformanceBaseline baseline = findBaseline(baselineId, user);
        List<RequestHistory> currentSamples = loadSamples(request == null ? null : request.sampleIds(), user);
        for (RequestHistory sample : currentSamples) {
            if (!baseline.getScenarioFingerprint().equals(scenarioFingerprint(sample))) {
                throw badRequest("Comparison samples must match the baseline method, URL, request body, and header names");
            }
            if (sample.getCreatedAt() == null || baseline.getWindowEnd() == null || !sample.getCreatedAt().isAfter(baseline.getWindowEnd())) {
                throw badRequest("Comparison samples must be later than the baseline run");
            }
        }

        MetricsSnapshot baselineMetrics = readJson(baseline.getMetricsJson(), MetricsSnapshot.class);
        MetricsSnapshot currentMetrics = metricService.aggregate(currentSamples);
        requireEnoughSamples(currentMetrics);
        Map<String, MetricComparison> comparisons = comparisonService.compare(baselineMetrics, currentMetrics);
        String status = comparisonService.overallStatus(comparisons);
        PerformanceRegressionReport report = new PerformanceRegressionReport();
        report.setUser(user);
        report.setBaselineId(baseline.getId());
        report.setBaselineName(baseline.getName());
        report.setStatus(status);
        report.setSampleCount(currentMetrics.sampleCount());
        report.setSampleIdsJson(writeJson(request.sampleIds()));
        report.setExecutionIdsJson(writeJson(currentSamples.stream().map(RequestHistory::getExecutionId).filter(Objects::nonNull).toList()));
        report.setTraceIdsJson(writeJson(currentSamples.stream().map(RequestHistory::getTraceId)
                .filter(id -> id != null && !id.isBlank() && !id.matches("0+")).toList()));
        report.setMetricsJson(writeJson(new PersistedComparison(baselineMetrics, currentMetrics, comparisons)));
        report.setThresholdsJson(writeJson(comparisonService.thresholds()));
        report.setWindowStart(currentMetrics.windowStart());
        report.setWindowEnd(currentMetrics.windowEnd());
        return toReport(reportRepository.save(report));
    }

    public RegressionReport getReport(long id) {
        User user = currentUserService.requireUser();
        return toReport(reportRepository.findByIdAndUser(id, user).orElseThrow(() -> notFound("Regression report not found")));
    }

    public PageEnvelope<RegressionReport> listReports(int page, int size) {
        validatePage(page, size);
        Page<PerformanceRegressionReport> results = reportRepository.findByUserOrderByCreatedAtDesc(
                currentUserService.requireUser(), PageRequest.of(page, size));
        return envelope(results.map(this::toReport));
    }

    private List<RequestHistory> loadSamples(List<Long> ids, User user) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_SAMPLES || ids.stream().anyMatch(Objects::isNull)) {
            throw badRequest("Select between 1 and " + MAX_SAMPLES + " execution samples");
        }
        if (new TreeSet<>(ids).size() != ids.size()) throw badRequest("Duplicate execution sample IDs are not allowed");
        List<RequestHistory> samples = historyRepository.findByIdInAndUser(ids, user);
        if (samples.size() != ids.size()) throw notFound("One or more execution samples were not found for this user");
        return samples;
    }

    private void requireEnoughSamples(MetricsSnapshot metrics) {
        if (metrics.sampleCount() < metricService.getMinimumSamples()) {
            throw badRequest("At least " + metricService.getMinimumSamples() + " complete executions are required; found " + metrics.sampleCount());
        }
    }

    private String scenarioFingerprint(RequestHistory sample) {
        String headerNames;
        try {
            Map<String, String> headers = objectMapper.readValue(sample.getHeaders(), new TypeReference<>() { });
            headerNames = headers == null ? "" : String.join(",", new TreeSet<>(headers.keySet()));
        } catch (Exception ignored) {
            headerNames = Objects.toString(sample.getHeaders(), "");
        }
        String canonical = Objects.toString(sample.getMethod(), "").toUpperCase() + "\n"
                + Objects.toString(sample.getUrl(), "") + "\n"
                + Objects.toString(sample.getBody(), "") + "\n" + headerNames;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private Baseline toBaseline(PerformanceBaseline baseline) {
        MetricsSnapshot metrics = readJson(baseline.getMetricsJson(), MetricsSnapshot.class);
        return new Baseline(baseline.getId(), baseline.getName(), baseline.getMethod(), baseline.getUrl(),
                baseline.getSampleCount(), baseline.getWindowStart(), baseline.getWindowEnd(), metrics.metrics(), baseline.getCreatedAt());
    }

    private RegressionReport toReport(PerformanceRegressionReport report) {
        PersistedComparison evidence = readJson(report.getMetricsJson(), PersistedComparison.class);
        Map<String, Object> rawThresholds = readJson(report.getThresholdsJson(), new TypeReference<>() { });
        Map<String, java.math.BigDecimal> thresholds = new LinkedHashMap<>();
        rawThresholds.forEach((key, value) -> thresholds.put(key, value instanceof java.math.BigDecimal decimal ? decimal : new java.math.BigDecimal(value.toString())));
        return new RegressionReport(report.getId(), report.getBaselineId(), report.getBaselineName(), report.getStatus(),
                report.getSampleCount(), readJson(report.getSampleIdsJson(), new TypeReference<List<Long>>() { }),
                readJson(report.getExecutionIdsJson(), new TypeReference<List<String>>() { }),
                readJson(report.getTraceIdsJson(), new TypeReference<List<String>>() { }), evidence.baseline(), evidence.current(),
                evidence.comparisons(), thresholds, report.getCreatedAt());
    }

    private HistorySample toSample(RequestHistory history) {
        return new HistorySample(history.getId(), history.getExecutionId(), history.getTraceId(), history.getMethod(),
                history.getUrl(), history.getStatusCode(), history.getResponseTime(), history.getCreatedAt());
    }

    private PerformanceBaseline findBaseline(long id, User user) {
        return baselineRepository.findByIdAndUserAndActiveTrue(id, user).orElseThrow(() -> notFound("Performance baseline not found"));
    }

    private <T> PageEnvelope<T> envelope(Page<T> page) {
        return new PageEnvelope<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw badRequest("page must be nonnegative and size must be between 1 and 100");
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not persist performance evidence"); }
    }

    private <T> T readJson(String json, Class<T> type) {
        try { return objectMapper.readValue(json, type); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Saved performance evidence is unreadable"); }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try { return objectMapper.readValue(json, type); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Saved performance evidence is unreadable"); }
    }

    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }

    private record PersistedComparison(MetricsSnapshot baseline, MetricsSnapshot current,
                                       Map<String, MetricComparison> comparisons) { }
}
