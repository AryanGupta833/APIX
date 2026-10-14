package com.Aryan.APIX.controller;

import com.Aryan.APIX.model.ApiExecutionResponse;
import com.Aryan.APIX.model.ApiRequest;
import com.Aryan.APIX.model.LocalAgentDtos.*;
import com.Aryan.APIX.service.LocalTestAgentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/apix/agents")
public class LocalTestAgentController {
    private final LocalTestAgentService service;
    public LocalTestAgentController(LocalTestAgentService service) { this.service = service; }

    @PostMapping("/pairings")
    @ResponseStatus(HttpStatus.CREATED)
    public PairingCreated createPairing(@RequestBody PairingCreateRequest request) { return service.createPairing(request); }

    @PostMapping("/pair")
    @ResponseStatus(HttpStatus.CREATED)
    public PairResponse pair(@RequestBody PairRequest request) { return service.pair(request); }

    @GetMapping
    public List<AgentView> list() { return service.list(); }

    @DeleteMapping("/{id}")
    public AgentView revoke(@PathVariable long id) { return service.revoke(id); }

    @PostMapping("/{id}/requests")
    public ApiExecutionResponse execute(@PathVariable long id, @RequestBody ApiRequest request) {
        return service.execute(id, request, null);
    }

    @GetMapping("/channel/jobs")
    public AgentJob poll(@RequestHeader("X-APiX-Agent-Credential") String credential,
                         @RequestParam(defaultValue = "20000") long waitMs) {
        return service.poll(credential, waitMs);
    }

    @PostMapping("/channel/jobs/{jobId}/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void result(@RequestHeader("X-APiX-Agent-Credential") String credential,
                       @PathVariable String jobId, @RequestBody AgentJobResult result) {
        service.acceptResult(credential, jobId, result);
    }
}
