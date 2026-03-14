package com.Aryan.APIX.config;

import com.Aryan.APIX.model.NetworkTimingContext;
import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InstrumentedHttpClientConfig {

    @Bean
    public HttpClient instrumentedHttpClient() {

        return HttpClient.create()

                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)

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