package com.Aryan.APIX.service;

import com.Aryan.APIX.model.ApiRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LoadTestServiceTest {
    @Test
    void rejectsUnboundedLoadTestCountsBeforeSendingAnyRequest() {
        RequestExecutionService requests = mock(RequestExecutionService.class);
        LoadTestService service = new LoadTestService();
        ReflectionTestUtils.setField(service, "requestExecutionService", requests);
        ReflectionTestUtils.setField(service, "maxRequests", 50);
        ApiRequest request = new ApiRequest();

        assertThrows(ResponseStatusException.class, () -> service.run(request, 0));
        assertThrows(ResponseStatusException.class, () -> service.run(request, 51));
        verifyNoInteractions(requests);
    }
}
