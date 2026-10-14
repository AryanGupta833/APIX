package com.Aryan.APIX.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import org.springframework.beans.factory.annotation.Value;



@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(HttpClient instrumentedHttpClient,
                               @Value("${apix.outbound.max-body-bytes:1048576}") int maxBodyBytes){
        if (maxBodyBytes < 1024 || maxBodyBytes > 10_485_760) throw new IllegalArgumentException("Invalid outbound body limit");
        return WebClient.builder().
                clientConnector(new ReactorClientHttpConnector(instrumentedHttpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxBodyBytes))
                .build();
    }

    @Bean
    public WebSocketClient webSocketClient(HttpClient instrumentedHttpClient) {
        return new ReactorNettyWebSocketClient(instrumentedHttpClient,
                () -> WebsocketClientSpec.builder().maxFramePayloadLength(64 * 1024));
    }
}
