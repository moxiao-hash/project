package com.moxiao.studypilot.shared.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestCorrelationFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void acceptsSafeRequestIdAndClearsMdcAfterRequest() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "web-123_A");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringRequest = new AtomicReference<>();
        FilterChain chain = (req, res) -> duringRequest.set(MDC.get("requestId"));

        filter.doFilter(request, response, chain);

        assertEquals("web-123_A", duringRequest.get());
        assertEquals("web-123_A", response.getHeader("X-Request-ID"));
        assertNull(MDC.get("requestId"));
    }

    @Test
    void replacesUnsafeOrOversizedRequestId() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "attacker\r\nX-Forged: yes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        String generated = response.getHeader("X-Request-ID");
        assertFalse(generated.contains("\r"));
        assertFalse(generated.contains("\n"));
        assertFalse(generated.length() > 64);
    }
}
