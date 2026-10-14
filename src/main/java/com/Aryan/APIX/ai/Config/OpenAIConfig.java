package com.Aryan.APIX.ai.Config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class OpenAIConfig {

    private final String openAiUrl;

    public OpenAIConfig(
            @Value("${openai.url}") String openAiUrl
    ) {
        this.openAiUrl = openAiUrl;
    }
    @Bean
    public WebClient openAIWebClient(HttpClient instrumentedHttpClient,
                                     @Value("${apix.outbound.max-body-bytes:1048576}") int maxBodyBytes){
        return WebClient.builder().baseUrl(openAiUrl)
                .clientConnector(new ReactorClientHttpConnector(instrumentedHttpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxBodyBytes))
                .build();
    }
}
