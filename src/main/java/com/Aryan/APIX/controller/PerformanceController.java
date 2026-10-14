package com.Aryan.APIX.controller;

import com.Aryan.APIX.model.PerformanceDtos.*;
import com.Aryan.APIX.service.PerformanceRegressionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/apix/performance")
public class PerformanceController {
    private final PerformanceRegressionService service;

    public PerformanceController(PerformanceRegressionService service) {
        this.service = service;
    }

    @GetMapping("/samples")
    public PageEnvelope<HistorySample> samples(@RequestParam String method,
                                                @RequestParam String url,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return service.samples(method, url, page, size);
    }

    @PostMapping("/baselines")
    public Baseline createBaseline(@RequestBody CreateBaselineRequest request) {
        return service.createBaseline(request);
    }

    @GetMapping("/baselines")
    public PageEnvelope<Baseline> listBaselines(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return service.listBaselines(page, size);
    }

    @GetMapping("/baselines/{id}")
    public Baseline getBaseline(@PathVariable long id) {
        return service.getBaseline(id);
    }

    @DeleteMapping("/baselines/{id}")
    public ResponseEntity<Void> deleteBaseline(@PathVariable long id) {
        service.deleteBaseline(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/baselines/{id}/comparisons")
    public RegressionReport compare(@PathVariable long id, @RequestBody CreateComparisonRequest request) {
        return service.compare(id, request);
    }

    @GetMapping("/reports/{id}")
    public RegressionReport getReport(@PathVariable long id) {
        return service.getReport(id);
    }

    @GetMapping("/reports")
    public PageEnvelope<RegressionReport> listReports(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return service.listReports(page, size);
    }
}
