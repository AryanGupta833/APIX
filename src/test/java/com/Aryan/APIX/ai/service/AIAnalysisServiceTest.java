package com.Aryan.APIX.ai.service;

import com.Aryan.APIX.ai.dto.AIRequest;
import com.Aryan.APIX.ai.dto.AIResponse;
import com.Aryan.APIX.ai.provider.AIProvider;
import com.Aryan.APIX.service.SensitiveDataRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AIAnalysisServiceTest {
    @Mock AIProvider provider;
    @Spy SensitiveDataRedactor redactor = new SensitiveDataRedactor();
    @InjectMocks AIAnalysisService service;

    @Test
    void requestScopedSecretsAreRedactedBeforeProviderReceivesDiagnostics() {
        when(provider.analyze(any())).thenReturn(new AIResponse());
        AIRequest request = new AIRequest();
        request.setRequestUrl("https://api.example.test/users?session=runtime-secret");
        request.setResponseBody("{\"message\":\"runtime-secret\"}");
        request.setTrace("failure details runtime-secret");
        request.setHeaders(Map.of("X-Debug", "runtime-secret", "Authorization", "Bearer other-secret"));

        service.analyzeFailure(request, List.of("runtime-secret"));

        verify(provider).analyze(org.mockito.ArgumentMatchers.argThat(safe -> {
            assertFalse(safe.getRequestUrl().contains("runtime-secret"));
            assertFalse(safe.getResponseBody().contains("runtime-secret"));
            assertFalse(safe.getTrace().contains("runtime-secret"));
            assertFalse(safe.getHeaders().get("X-Debug").contains("runtime-secret"));
            assertFalse(safe.getHeaders().get("Authorization").contains("other-secret"));
            return true;
        }));
    }
}
