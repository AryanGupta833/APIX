package com.Aryan.APIX.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.util.ReflectionTestUtils;
import com.Aryan.APIX.model.ApiRequest;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboundDestinationPolicyTest {
    private final OutboundDestinationPolicy policy = new OutboundDestinationPolicy("");

    @Test
    void acceptsPublicHttpAndHttpsUrlsAndTrailingDotNormalization() {
        assertEquals("https", policy.validateUrl("https://api.example.test/v1").getScheme());
        assertEquals("api.example.test.", policy.validateUrl("http://api.example.test./").getHost());
        assertDoesNotThrow(() -> policy.validateResolved("api.example.test", List.of(ip("8.8.8.8"), ip("2606:4700:4700::1111"))));
    }

    @Test
    void websocketSchemesUseTheSamePublicDestinationPolicyAsHttpHandshakes() {
        assertEquals("wss", policy.validateWebSocketUrl("wss://api.example.test/socket").getScheme());
        assertEquals("ws", policy.validateWebSocketUrl("ws://api.example.test/socket").getScheme());
        for (String value : List.of("http://api.example.test/socket", "ws://127.0.0.1:8080/socket",
                "wss://[::1]/socket", "ws://user:pass@api.example.test/socket", "ws://api.example.test/socket#fragment"))
            assertThrows(ResponseStatusException.class, () -> policy.validateWebSocketUrl(value), value);
        OutboundDestinationPolicy testLocalPolicy = new OutboundDestinationPolicy("127.0.0.1");
        assertDoesNotThrow(() -> testLocalPolicy.validateWebSocketUrl("ws://127.0.0.1:8080/socket"));
    }

    @Test
    void rejectsMalformedSchemesCredentialsPortsAndAmbiguousNumericAddresses() {
        for (String url : List.of("file:///etc/passwd", "ftp://example.test", "http://user:pass@example.test",
                "http://example.test:70000", "http://example.test:0", "http://2130706433", "http://0177.0.0.1",
                "http://0x7f000001", "http://[::1]", "http://127.0.0.1/")) {
            assertThrows(ResponseStatusException.class, () -> policy.validateUrl(url), url);
        }
    }

    @Test
    void rejectsAllNonPublicAddressClassesAndMixedDnsAnswers() {
        for (String address : List.of("0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.1.2",
                "172.20.1.2", "192.0.2.1", "192.168.1.1", "198.18.0.1", "198.51.100.2", "203.0.113.9",
                "224.0.0.1", "240.0.0.1", "::", "::1", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1", "2002:7f00:1::1")) {
            assertThrows(ResponseStatusException.class, () -> policy.validateResolved("public.example", List.of(ip(address))), address);
        }
        assertThrows(ResponseStatusException.class,
                () -> policy.validateResolved("mixed.example", List.of(ip("8.8.8.8"), ip("10.0.0.1"))));
        byte[] mappedLoopback = new byte[16]; mappedLoopback[10] = (byte) 0xff; mappedLoopback[11] = (byte) 0xff;
        mappedLoopback[12] = 127; mappedLoopback[15] = 1;
        assertThrows(ResponseStatusException.class, () -> policy.validateResolved("mapped.example",
                List.of(Inet6Address.getByAddress(null, mappedLoopback, -1))));
    }

    @Test
    void exactLocalAllowlistEnablesOnlyThatHostForLoopbackTesting() {
        OutboundDestinationPolicy localPolicy = new OutboundDestinationPolicy("localhost, 127.0.0.1");
        assertDoesNotThrow(() -> localPolicy.validateUrl("http://localhost:8080/health"));
        assertDoesNotThrow(() -> localPolicy.validateResolved("localhost", List.of(ip("127.0.0.1"), ip("::1"))));
        assertThrows(ResponseStatusException.class, () -> localPolicy.validateResolved("other.localhost", List.of(ip("127.0.0.1"))));
        assertThrows(ResponseStatusException.class, () -> localPolicy.validateResolved("localhost", List.of(ip("127.0.0.1"), ip("8.8.8.8"))));
        assertThrows(ResponseStatusException.class, () -> localPolicy.validateResolved("localhost", List.of(ip("169.254.169.254"))));
        assertThrows(ResponseStatusException.class, () -> new OutboundDestinationPolicy("168.63.129.16")
                .validateResolved("168.63.129.16", List.of(ip("168.63.129.16"))));
    }

    @Test
    void badPortAndHostInputsNeverPassPreflight() {
        for (String url : List.of("http:///path", "http://example.test:-1", "http://example.test:abc", "http://example.test:",
                "http://example.test..", "http://exa mple.test"))
            assertThrows(ResponseStatusException.class, () -> policy.validateUrl(url));
    }

    @Test
    void metadataDestinationsCannotBeEnabledByTheLocalAllowlist() {
        OutboundDestinationPolicy permissiveLookingConfig = new OutboundDestinationPolicy("metadata.google.internal,169.254.169.254,168.63.129.16");
        assertThrows(ResponseStatusException.class, () -> permissiveLookingConfig.validateUrl("http://metadata.google.internal/computeMetadata/v1/"));
        assertThrows(ResponseStatusException.class, () -> permissiveLookingConfig.validateResolved("169.254.169.254", List.of(ip("169.254.169.254"))));
        assertThrows(ResponseStatusException.class, () -> permissiveLookingConfig.validateResolved("168.63.129.16", List.of(ip("168.63.129.16"))));
    }

    @Test
    void requestExecutorRejectsLiteralLoopbackBeforeCallingTheHttpClient() {
        RequestExecutionService executor = new RequestExecutionService();
        WebClient client = mock(WebClient.class);
        ReflectionTestUtils.setField(executor, "webClient", client);
        ReflectionTestUtils.setField(executor, "destinationPolicy", policy);
        ApiRequest request = new ApiRequest(); request.setMethod("GET"); request.setUrl("http://127.0.0.1:8080/");

        assertThrows(ResponseStatusException.class, () -> executor.execute(request));
        verifyNoInteractions(client);
    }

    private static InetAddress ip(String value) {
        try { return InetAddress.getByName(value); }
        catch (Exception e) { throw new AssertionError("Test literal was invalid", e); }
    }
}
