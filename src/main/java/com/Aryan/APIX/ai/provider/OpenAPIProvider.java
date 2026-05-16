package com.Aryan.APIX.ai.provider;
import com.Aryan.APIX.ai.dto.AIRequest;
import com.Aryan.APIX.ai.dto.AIResponse;
import com.Aryan.APIX.ai.provider.AIProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Component
public class OpenAPIProvider implements AIProvider {

    private final WebClient webClient;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.model}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAPIProvider(WebClient openAIWebClient) {
        this.webClient = openAIWebClient;
    }

    @Override
    public AIResponse analyze(AIRequest request) {

        try {

            String prompt = buildPrompt(request);

            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0.2,
                    "max_tokens", 300,
                    "messages", List.of(
                            Map.of(
                                    "role", "system",
                                    "content", """
                                    You are APIX X-Ray Engine.
                                    Return ONLY valid JSON.
                                    """
                            ),
                            Map.of(
                                    "role", "user",
                                    "content", prompt
                            )
                    )
            );

            String response = webClient.post()
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            System.out.println(response);

            return parseResponse(response);

        } catch (Exception e) {

            AIResponse error = new AIResponse();

            error.setFailureType("AI_ANALYSIS_FAILED");
            error.setLayer("AI_LAYER");
            error.setReason(e.getMessage());
            error.setFixes(List.of("Check AI provider configuration"));
            error.setConfidence(0.0);

            return error;
        }
    }

    private String buildPrompt(AIRequest request) {

        return """
                Analyze this API failure.

                URL: %s
                Method: %s
                Status Code: %d
                Response Body: %s
                Trace: %s
                Latency: %d ms

                Return STRICT JSON:
                {
                  "failureType":"",
                  "layer":"",
                  "reason":"",
                  "fixes":[],
                  "confidence":0.0
                }
                """
                .formatted(
                        request.getRequestUrl(),
                        request.getMethod(),
                        request.getStatusCode(),
                        request.getResponseBody(),
                        request.getTrace(),
                        request.getLatency()
                );
    }

    private AIResponse parseResponse(String response) {

        try {

            JsonNode root = objectMapper.readTree(response);

            String content = root
                    .get("choices")
                    .get(0)
                    .get("message")
                    .get("content")
                    .asText();

            content = content
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();

            return objectMapper.readValue(content, AIResponse.class);

        } catch (Exception e) {

            AIResponse error = new AIResponse();

            error.setFailureType("PARSING_ERROR");
            error.setLayer("AI_LAYER");
            error.setReason("Failed to parse AI response");
            error.setFixes(List.of("Verify AI JSON structure"));
            error.setConfidence(0.0);

            return error;
        }
    }
}