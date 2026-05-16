package com.Aryan.APIX.ai.Config;


import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class OpenAIConfig {

    private final String openAiUrl;

    public OpenAIConfig(
            @Value("${openai.url}") String openAiUrl
    ) {
        this.openAiUrl = openAiUrl;
    }
    @PostConstruct
    public void test() {
        System.out.println("OPENAI URL = " + openAiUrl);
    }

    @Bean
    public WebClient openAIWebClient(){
        return WebClient.builder().baseUrl(openAiUrl).build();
    }
}
