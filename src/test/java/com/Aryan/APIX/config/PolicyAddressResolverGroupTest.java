package com.Aryan.APIX.config;

import com.Aryan.APIX.service.OutboundDestinationPolicy;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PolicyAddressResolverGroupTest {
    @Test
    void validatesEveryDnsAnswerAndReturnsTheCheckedConnectionAddresses() throws Exception {
        var publicAnswer = ip("93.184.216.34");
        var allowedResolver = resolver(List.of(publicAnswer), false, 1000);
        try {
            var future = allowedResolver.resolveAll(InetSocketAddress.createUnresolved("multi.example.test", 443));
            assertTrue(future.awaitUninterruptibly(1, TimeUnit.SECONDS));
            assertTrue(future.isSuccess());
            assertEquals(publicAnswer, future.getNow().getFirst().getAddress());
        } finally { allowedResolver.executor.shutdownGracefully().syncUninterruptibly(); }

        var mixedResolver = resolver(List.of(publicAnswer, ip("10.0.0.8")), false, 1000);
        try {
            var future = mixedResolver.resolveAll(InetSocketAddress.createUnresolved("mixed.example.test", 443));
            assertTrue(future.awaitUninterruptibly(1, TimeUnit.SECONDS));
            assertFalse(future.isSuccess(), "one forbidden address must reject the complete DNS result");
        } finally { mixedResolver.executor.shutdownGracefully().syncUninterruptibly(); }
    }

    @Test
    void failsClosedOnDnsErrorsAndResolutionTimeouts() {
        var failedResolver = resolver(List.of(), true, 1000);
        try {
            var future = failedResolver.resolveAll(InetSocketAddress.createUnresolved("missing.example.test", 443));
            assertTrue(future.awaitUninterruptibly(1, TimeUnit.SECONDS));
            assertFalse(future.isSuccess());
        } finally { failedResolver.executor.shutdownGracefully().syncUninterruptibly(); }

        var timedOutResolver = resolver(List.of(), false, 100);
        try {
            var future = timedOutResolver.resolveAll(InetSocketAddress.createUnresolved("slow.example.test", 443));
            assertTrue(future.awaitUninterruptibly(1, TimeUnit.SECONDS));
            assertFalse(future.isSuccess());
            assertEquals("DNS lookup timed out", future.cause().getMessage());
        } finally { timedOutResolver.executor.shutdownGracefully().syncUninterruptibly(); }
    }

    private static TestResolver resolver(List<InetAddress> answers, boolean fail, long timeoutMs) {
        DefaultEventExecutor executor = new DefaultEventExecutor();
        AddressResolverGroup<InetSocketAddress> fakeDns = new AddressResolverGroup<>() {
            @Override protected AddressResolver<InetSocketAddress> newResolver(EventExecutor eventExecutor) {
                return new AbstractAddressResolver<>(eventExecutor, InetSocketAddress.class) {
                    @Override protected boolean doIsResolved(InetSocketAddress address) { return false; }
                    @Override protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                        if (fail) promise.tryFailure(new IllegalStateException("private resolver detail"));
                        else if (answers.isEmpty()) { /* deterministic timeout simulation */ }
                        else promise.trySuccess(new InetSocketAddress(answers.getFirst(), address.getPort()));
                    }
                    @Override protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
                        if (fail) promise.tryFailure(new IllegalStateException("private resolver detail"));
                        else if (answers.isEmpty()) { /* deterministic timeout simulation */ }
                        else promise.trySuccess(answers.stream().map(ip -> new InetSocketAddress(ip, address.getPort())).toList());
                    }
                };
            }
        };
        // Empty-answer behavior is a timeout unless explicit failure is requested.
        OutboundDestinationPolicy policy = new OutboundDestinationPolicy("");
        return new TestResolver(executor, new PolicyAddressResolverGroup(policy, Duration.ofMillis(timeoutMs), fakeDns).getResolver(executor));
    }

    private static InetAddress ip(String address) {
        try { return InetAddress.getByName(address); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private record TestResolver(DefaultEventExecutor executor, AddressResolver<InetSocketAddress> resolver) {
        io.netty.util.concurrent.Future<List<InetSocketAddress>> resolveAll(InetSocketAddress address) { return resolver.resolveAll(address); }
    }
}
