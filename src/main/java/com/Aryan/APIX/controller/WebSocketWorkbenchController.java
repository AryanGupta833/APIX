package com.Aryan.APIX.controller;

import com.Aryan.APIX.model.WebSocketDtos.ConnectRequest;
import com.Aryan.APIX.model.WebSocketDtos.SendMessageRequest;
import com.Aryan.APIX.model.WebSocketDtos.SessionView;
import com.Aryan.APIX.service.WebSocketWorkbenchService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/apix/websocket/sessions")
public class WebSocketWorkbenchController {
    private final WebSocketWorkbenchService service;

    public WebSocketWorkbenchController(WebSocketWorkbenchService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView connect(@RequestBody ConnectRequest request) { return service.connect(request); }

    @GetMapping
    public List<SessionView> list() { return service.list(); }

    @GetMapping("/{id}")
    public SessionView get(@PathVariable String id) { return service.get(id); }

    @PostMapping("/{id}/messages")
    public SessionView send(@PathVariable String id, @RequestBody SendMessageRequest request) {
        return service.send(id, request == null ? null : request.message());
    }

    @DeleteMapping("/{id}")
    public SessionView close(@PathVariable String id) { return service.close(id); }
}
