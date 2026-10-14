package com.Aryan.APIX.config;

import com.Aryan.APIX.model.NetworkTimingContext;
import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.NameResolverProvider;
import io.netty.resolver.AddressResolverGroup;
import java.net.InetSocketAddress;
import java.time.Duration;

@Configuration
public class InstrumentedHttpClientConfig {

    @Bean(destroyMethod = "dispose")
    public ConnectionProvider outboundConnectionProvider(
            @Value("${apix.outbound.max-connections:50}") int maxConnections,
            @Value("${apix.outbound.max-pending-acquires:100}") int maxPendingAcquires) {
        if (maxConnections < 1 || maxConnections > 200 || maxPendingAcquires < 0 || maxPendingAcquires > 1000)
            throw new IllegalArgumentException("Invalid outbound connection pool limits");
        return ConnectionProvider.builder("apix-outbound")
                .maxConnections(maxConnections)
                .pendingAcquireMaxCount(maxPendingAcquires)
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Bean(destroyMethod = "dispose")
    public LoopResources outboundDnsLoops() { return LoopResources.create("apix-dns", 2, true); }

    @Bean(destroyMethod = "close")
    public AddressResolverGroup<InetSocketAddress> outboundDnsResolverGroup(
            LoopResources outboundDnsLoops,
            @Value("${apix.outbound.dns-timeout-ms:3000}") long dnsTimeoutMs) {
        return NameResolverProvider.builder().queryTimeout(Duration.ofMillis(dnsTimeoutMs))
                .completeOncePreferredResolved(false).runOn(outboundDnsLoops, true).build()
                .newNameResolverGroup(outboundDnsLoops, true);
    }

    @Bean
    public HttpClient instrumentedHttpClient(ConnectionProvider outboundConnectionProvider,
                                             com.Aryan.APIX.service.OutboundDestinationPolicy destinationPolicy,
                                             AddressResolverGroup<InetSocketAddress> outboundDnsResolverGroup,
                                             @Value("${apix.outbound.connect-timeout-ms:5000}") int connectTimeoutMs,
                                             @Value("${apix.outbound.response-timeout-ms:30000}") long responseTimeoutMs,
                                             @Value("${apix.outbound.dns-timeout-ms:3000}") long dnsTimeoutMs) {

        if (connectTimeoutMs < 100 || connectTimeoutMs > 30_000 || responseTimeoutMs < 100 || responseTimeoutMs > 60_000
                || dnsTimeoutMs < 100 || dnsTimeoutMs > 10_000) throw new IllegalArgumentException("Invalid outbound timeout configuration");
        return HttpClient.create(outboundConnectionProvider)

                .resolver(new PolicyAddressResolverGroup(destinationPolicy, Duration.ofMillis(dnsTimeoutMs), outboundDnsResolverGroup))
                .followRedirect(false)
                .disableRetry(true)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs))

                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)

                .doOnRequest((req, conn) -> {

                    NetworkTimingContext ctx =
                            new NetworkTimingContext();

                    ctx.setRequestSent(System.currentTimeMillis());

                    conn.channel().attr(
                            io.netty.util.AttributeKey.valueOf("networkTiming")
                    ).set(ctx);

                })

                .doOnResponse((res, conn) -> {

                    NetworkTimingContext ctx =
                            (NetworkTimingContext) conn.channel()
                                    .attr(io.netty.util.AttributeKey.valueOf("networkTiming"))
                                    .get();

                    if(ctx != null){
                        ctx.setResponseReceived(System.currentTimeMillis());
                    }

                });
    }
}
