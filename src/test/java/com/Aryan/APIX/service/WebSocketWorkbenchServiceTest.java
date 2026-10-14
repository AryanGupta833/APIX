package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.User;
import com.Aryan.APIX.model.WebSocketDtos.ConnectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import org.reactivestreams.Publisher;
import java.net.URI;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketWorkbenchServiceTest {
    @Test
    void invalidOrUnapprovedTargetsAreRejectedBeforeAClientConnectionIsStarted() {
        User owner = new User(); owner.setId(10L);
        CurrentUserService users = mock(CurrentUserService.class); when(users.requireUser()).thenReturn(owner);
        WebSocketClient client = mock(WebSocketClient.class);
        WebSocketWorkbenchService service = new WebSocketWorkbenchService(client,
                new OutboundDestinationPolicy(""), users, new SensitiveDataRedactor());

        ResponseStatusException blocked = assertThrows(ResponseStatusException.class,
                () -> service.connect(new ConnectRequest("ws://127.0.0.1:8080/socket", Map.of())));
        assertEquals(HttpStatus.BAD_REQUEST, blocked.getStatusCode());
        verifyNoInteractions(client);
    }

    @Test
    void invalidProtocolHeadersAreRejectedWithoutOpeningASession() {
        User owner = new User(); owner.setId(11L);
        CurrentUserService users = mock(CurrentUserService.class); when(users.requireUser()).thenReturn(owner);
        WebSocketClient client = mock(WebSocketClient.class);
        WebSocketWorkbenchService service = new WebSocketWorkbenchService(client,
                new OutboundDestinationPolicy(""), users, new SensitiveDataRedactor());

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> service.connect(new ConnectRequest("wss://api.example.test/socket", Map.of("Sec-WebSocket-Key", "forged"))));
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        verifyNoInteractions(client);
    }

    @Test
    void sessionsAreNotAccessibleAcrossOwners() {
        User owner = new User(); owner.setId(12L);
        CurrentUserService users = mock(CurrentUserService.class); when(users.requireUser()).thenReturn(owner);
        WebSocketWorkbenchService service = new WebSocketWorkbenchService(mock(WebSocketClient.class),
                new OutboundDestinationPolicy(""), users, new SensitiveDataRedactor());

        ResponseStatusException missing = assertThrows(ResponseStatusException.class, () -> service.get("not-owned"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    @Test
    void sessionCreationReturnsBeforeHandshakeAndCancellationDisposesPendingConnection() throws Exception {
        User owner = new User(); owner.setId(15L);
        CurrentUserService users = mock(CurrentUserService.class); when(users.requireUser()).thenReturn(owner);
        CountDownLatch cancelled = new CountDownLatch(1);
        WebSocketClient client = mock(WebSocketClient.class);
        when(client.execute(any(), any(), any())).thenReturn(Mono.create(sink -> sink.onCancel(cancelled::countDown)));
        WebSocketWorkbenchService service = new WebSocketWorkbenchService(client,
                new OutboundDestinationPolicy(""), users, new SensitiveDataRedactor());

        var connecting = service.connect(new ConnectRequest("wss://api.example.test/socket", Map.of()));
        assertEquals("CONNECTING", connecting.state());
        var closed = service.close(connecting.id());

        assertEquals("DISCONNECTED", closed.state());
        assertTrue(cancelled.await(1, TimeUnit.SECONDS), "closing a pending session should cancel its transport");
    }

    @Test
    void suiteOperationsSendAndReceiveOrderedFramesWithOwnerChecksAndCancellation() throws Exception {
        WebSocketClient client = mock(WebSocketClient.class);
        WebSocketSession socket = mock(WebSocketSession.class);
        HandshakeInfo handshake = mock(HandshakeInfo.class);
        HttpHeaders responseHeaders = new HttpHeaders(); responseHeaders.add("X-Echo", "agent-secret"); responseHeaders.add("Set-Cookie", "session=secret");
        when(socket.getHandshakeInfo()).thenReturn(handshake);
        when(handshake.getHeaders()).thenReturn(responseHeaders);
        Sinks.Many<WebSocketMessage> incoming = Sinks.many().multicast().onBackpressureBuffer();
        when(socket.receive()).thenReturn(incoming.asFlux());
        ConcurrentLinkedQueue<String> sent = new ConcurrentLinkedQueue<>();
        when(socket.send(any())).thenAnswer(call -> Flux.from((Publisher<WebSocketMessage>) call.getArgument(0))
                .doOnNext(message -> sent.add(message.getPayloadAsText())).then());
        when(socket.textMessage(anyString())).thenAnswer(call -> {
            WebSocketMessage message = mock(WebSocketMessage.class);
            when(message.getPayloadAsText()).thenReturn(call.getArgument(0));
            when(message.getType()).thenReturn(WebSocketMessage.Type.TEXT);
            return message;
        });
        when(socket.close(any())).thenReturn(Mono.empty());
        AtomicReference<WebSocketHandler> connectionHandler = new AtomicReference<>();
        when(client.execute(any(URI.class), any(HttpHeaders.class), any())).thenAnswer(call -> {
            connectionHandler.set(call.getArgument(2));
            return Mono.never();
        });
        WebSocketWorkbenchService service = new WebSocketWorkbenchService(client, new OutboundDestinationPolicy(""),
                mock(CurrentUserService.class), new SensitiveDataRedactor());

        var created = service.connectForOwner(700L, new ConnectRequest("wss://api.example.test/socket",
                Map.of("Authorization", "agent-secret")), List.of("agent-secret"));
        reactor.core.Disposable transport = connectionHandler.get().handle(socket).subscribe();
        service.awaitConnectedForOwner(created.id(), 700L, 1000);
        var connected = service.getForOwner(created.id(), 700L);
        assertEquals("CONNECTED", connected.state());
        assertEquals("[REDACTED]", connected.handshakeHeaders().get("X-Echo"));
        assertEquals("[REDACTED]", connected.handshakeHeaders().get("Set-Cookie"));

        service.sendForOwner(created.id(), 700L, "hello");
        assertEquals(List.of("hello"), new ArrayList<>(sent));
        WebSocketMessage incomingMessage = mock(WebSocketMessage.class);
        when(incomingMessage.getType()).thenReturn(WebSocketMessage.Type.TEXT);
        when(incomingMessage.getPayloadAsText()).thenReturn("agent-secret");
        assertEquals(Sinks.EmitResult.OK, incoming.tryEmitNext(incomingMessage));
        var received = service.awaitMessageForOwner(created.id(), 700L, 0, 1000);
        assertEquals("[REDACTED]", received.payload());

        User other = new User(); other.setId(701L);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.sendForOwner(created.id(), other.getId(), "forbidden")).getStatusCode());
        AtomicReference<Throwable> interrupted = new AtomicReference<>();
        CountDownLatch waiterStarted = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            waiterStarted.countDown();
            try { service.awaitMessageForOwner(created.id(), 700L, 1, 10_000); }
            catch (Throwable error) { interrupted.set(error); }
        });
        waiter.start();
        assertTrue(waiterStarted.await(1, TimeUnit.SECONDS));
        waiter.interrupt(); waiter.join(1000);
        assertFalse(waiter.isAlive());
        assertInstanceOf(InterruptedException.class, interrupted.get());
        service.closeForOwner(created.id(), 700L);
        transport.dispose();
        assertEquals("DISCONNECTED", service.getForOwner(created.id(), 700L).state());
    }

    @Test
    void controlledLocalWebSocketEchoUsesPolicyConfiguredTransportAndRedactsReceivedSecrets() throws Exception {
        CountDownLatch readyObserved = new CountDownLatch(1);
        CountDownLatch echoObserved = new CountDownLatch(1);
        var server = reactor.netty.http.server.HttpServer.create().host("127.0.0.1").port(0)
                .route(routes -> routes.ws("/echo", (in, out) -> out.sendString(
                        Mono.just("ready").concatWith(in.receive().asString(java.nio.charset.StandardCharsets.UTF_8)))))
                .bindNow();
        SensitiveDataRedactor redactor = new SensitiveDataRedactor() {
            @Override public String redactText(String value, java.util.Collection<String> secrets) {
                String result = super.redactText(value, secrets);
                if ("ready".equals(value)) readyObserved.countDown();
                if ("agent-secret".equals(value)) echoObserved.countDown();
                return result;
            }
        };
        User owner = new User(); owner.setId(13L);
        CurrentUserService users = mock(CurrentUserService.class); when(users.requireUser()).thenReturn(owner);
        OutboundDestinationPolicy policy = new OutboundDestinationPolicy("127.0.0.1");
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                .resolver(new com.Aryan.APIX.config.PolicyAddressResolverGroup(policy, java.time.Duration.ofSeconds(2)))
                .followRedirect(false).disableRetry(true);
        WebSocketClient client = new org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient(httpClient,
                () -> reactor.netty.http.client.WebsocketClientSpec.builder().maxFramePayloadLength(64 * 1024));
        WebSocketWorkbenchService service = new WebSocketWorkbenchService(client, policy, users, redactor);
        try {
            var initial = service.connect(new ConnectRequest("ws://127.0.0.1:" + server.port() + "/echo",
                    Map.of("Authorization", "agent-secret")));
            assertTrue(Set.of("CONNECTING", "CONNECTED").contains(initial.state()));
            assertTrue(readyObserved.await(5, TimeUnit.SECONDS), "controlled echo server should send its ready frame");
            var connected = service.get(initial.id());
            assertEquals("CONNECTED", connected.state());
            assertEquals(101, connected.handshakeStatus());
            User other = new User(); other.setId(14L);
            when(users.requireUser()).thenReturn(other);
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                    () -> service.get(connected.id())).getStatusCode());
            when(users.requireUser()).thenReturn(owner);

            service.send(connected.id(), "agent-secret");

            assertTrue(echoObserved.await(5, TimeUnit.SECONDS), "controlled echo should be received");
            var updated = service.get(connected.id());
            assertEquals("ready", updated.messages().getFirst().payload());
            assertEquals("[REDACTED]", updated.messages().get(1).payload());
            service.close(connected.id());
        } finally {
            service.closeAll();
            server.disposeNow();
        }
    }
}
