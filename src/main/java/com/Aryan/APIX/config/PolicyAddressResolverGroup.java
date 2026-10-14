package com.Aryan.APIX.config;

import com.Aryan.APIX.service.OutboundDestinationPolicy;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Resolves all DNS answers, rejects mixed public/private answers, then connects to a checked IP. */
public final class PolicyAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {
    private final OutboundDestinationPolicy policy;
    private final Duration resolutionTimeout;
    private final AddressResolverGroup<InetSocketAddress> delegateGroup;

    public PolicyAddressResolverGroup(OutboundDestinationPolicy policy, Duration resolutionTimeout) {
        this(policy, resolutionTimeout, DefaultAddressResolverGroup.INSTANCE);
    }

    public PolicyAddressResolverGroup(OutboundDestinationPolicy policy, Duration resolutionTimeout,
                                      AddressResolverGroup<InetSocketAddress> delegateGroup) {
        this.policy = policy; this.resolutionTimeout = resolutionTimeout; this.delegateGroup = delegateGroup;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) throws Exception {
        AddressResolver<InetSocketAddress> delegate = delegateGroup.getResolver(executor);
        return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
            @Override protected boolean doIsResolved(InetSocketAddress address) { return false; }

            @Override protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                var timeout = executor.schedule(() -> promise.tryFailure(new IllegalArgumentException("DNS lookup timed out")),
                        resolutionTimeout.toMillis(), TimeUnit.MILLISECONDS);
                delegate.resolveAll(address).addListener((Future<List<InetSocketAddress>> result) -> {
                    timeout.cancel(false);
                    try { promise.trySuccess(checkedAnswers(address, result).getFirst()); }
                    catch (RuntimeException error) { promise.tryFailure(error); }
                });
            }

            @Override protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
                var timeout = executor.schedule(() -> promise.tryFailure(new IllegalArgumentException("DNS lookup timed out")),
                        resolutionTimeout.toMillis(), TimeUnit.MILLISECONDS);
                delegate.resolveAll(address).addListener((Future<List<InetSocketAddress>> result) -> {
                    timeout.cancel(false);
                    try { promise.trySuccess(checkedAnswers(address, result)); }
                    catch (RuntimeException error) { promise.tryFailure(error); }
                });
            }

            private List<InetSocketAddress> checkedAnswers(InetSocketAddress source, Future<List<InetSocketAddress>> result) {
                if (!result.isSuccess()) throw new IllegalArgumentException("DNS resolution failed");
                List<InetSocketAddress> answers = result.getNow();
                try {
                    policy.validateResolved(source.getHostString(), answers.stream().map(InetSocketAddress::getAddress).toList());
                    return answers;
                } catch (RuntimeException rejected) {
                    throw new com.Aryan.APIX.service.OutboundPolicyBlockedException();
                }
            }
        };
    }
}
