package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.PerformanceBaseline;
import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.entity.User;
import com.Aryan.APIX.model.PerformanceDtos.CreateBaselineRequest;
import com.Aryan.APIX.repository.PerformanceBaselineRepository;
import com.Aryan.APIX.repository.PerformanceRegressionReportRepository;
import com.Aryan.APIX.repository.RequestHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PerformanceRegressionServiceTest {
    private final PerformanceBaselineRepository baselines = mock(PerformanceBaselineRepository.class);
    private final PerformanceRegressionReportRepository reports = mock(PerformanceRegressionReportRepository.class);
    private final RequestHistoryRepository history = mock(RequestHistoryRepository.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final ObjectMapper mapper = mock(ObjectMapper.class);
    private final PerformanceRegressionService service = new PerformanceRegressionService(baselines, reports, history,
            currentUser, new PerformanceMetricService(), new PerformanceComparisonService(), mapper);

    @Test
    void baselineLookupIsScopedToTheAuthenticatedOwner() {
        User owner = new User();
        when(currentUser.requireUser()).thenReturn(owner);
        when(baselines.findByIdAndUserAndActiveTrue(77L, owner)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.getBaseline(77));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(baselines).findByIdAndUserAndActiveTrue(77L, owner);
    }

    @Test
    void rejectsBaselineWithInsufficientCompleteSamples() {
        User owner = new User();
        when(currentUser.requireUser()).thenReturn(owner);
        when(mapper.readValue(any(String.class), org.mockito.ArgumentMatchers.<TypeReference<Map<String, String>>>any())).thenReturn(null);
        when(history.findByIdInAndUser(List.of(1L, 2L, 3L, 4L), owner)).thenReturn(List.of(
                sample(1, 100), sample(2, 110), sample(3, 120), sample(4, 130)));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.createBaseline(new CreateBaselineRequest("too small", List.of(1L, 2L, 3L, 4L))));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(baselines);
    }

    private RequestHistory sample(long id, long latency) {
        RequestHistory sample = new RequestHistory();
        sample.setId(id);
        sample.setMethod("GET");
        sample.setUrl("https://example.test/items");
        sample.setStatusCode(200);
        sample.setResponseTime(latency);
        sample.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(id));
        sample.setHeaders("{}");
        return sample;
    }
}
