package com.Aryan.APIX.controller;

import com.Aryan.APIX.model.PerformanceDtos.RegressionReport;
import com.Aryan.APIX.model.SuiteDtos.*;
import com.Aryan.APIX.service.ApiTestSuiteService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/apix/suites")
public class ApiTestSuiteController {
    private final ApiTestSuiteService service;
    public ApiTestSuiteController(ApiTestSuiteService service) { this.service = service; }

    @GetMapping public PageEnvelope<SuiteView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return service.list(page, size); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public SuiteView create(@RequestBody SuiteUpsert request) { return service.create(request); }
    @GetMapping("/{id}") public SuiteView get(@PathVariable long id) { return service.get(id); }
    @PutMapping("/{id}") public SuiteView update(@PathVariable long id, @RequestBody SuiteUpsert request) { return service.update(id, request); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable long id) { service.delete(id); }
    @PostMapping("/{id}/clone") @ResponseStatus(HttpStatus.CREATED) public SuiteView cloneSuite(@PathVariable long id) { return service.cloneSuite(id); }

    @PostMapping("/{id}/cases") @ResponseStatus(HttpStatus.CREATED) public SuiteView addCase(@PathVariable long id, @RequestBody SuiteCase testCase) { return service.addCase(id, testCase); }
    @PutMapping("/{id}/cases/{caseId}") public SuiteView updateCase(@PathVariable long id, @PathVariable String caseId, @RequestBody SuiteCase testCase) { return service.updateCase(id, caseId, testCase); }
    @DeleteMapping("/{id}/cases/{caseId}") public SuiteView removeCase(@PathVariable long id, @PathVariable String caseId) { return service.removeCase(id, caseId); }
    @PutMapping("/{id}/cases/order") public SuiteView reorder(@PathVariable long id, @RequestBody CaseOrder order) { return service.reorder(id, order); }

    @PostMapping("/{id}/runs") public RunView run(@PathVariable long id, @RequestBody(required = false) RunRequest request) { return service.run(id, request); }
    @GetMapping("/{id}/runs") public PageEnvelope<RunView> listRuns(@PathVariable long id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return service.listRuns(id, page, size); }
    @GetMapping("/runs/{runId}") public RunView getRun(@PathVariable long runId) { return service.getRun(runId); }
    @PostMapping("/runs/{runId}/cancel") public RunView cancelRun(@PathVariable long runId) { return service.cancelRun(runId); }
    @PostMapping("/runs/{runId}/performance-comparisons") public RegressionReport compareRun(@PathVariable long runId, @RequestParam long baselineId) { return service.compareRun(runId, baselineId); }
}
