package com.moxiao.studypilot.agent.application;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

import com.moxiao.studypilot.shared.web.RequestCorrelationFilter;

import java.net.http.HttpRequest;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentGatewayServiceTest {

    @Test
    void forwardsCurrentRequestIdWithoutExposingSecrets() {
        AgentGatewayService service = new AgentGatewayService(
                "http://localhost:8000",
                "internal-secret",
                new ObjectMapper()
        );
        MDC.put(RequestCorrelationFilter.MDC_KEY, "java-request-7");
        try {
            HttpRequest request = service.baseRequest("/health").GET().build();
            assertEquals(
                    "java-request-7",
                    request.headers().firstValue(RequestCorrelationFilter.HEADER).orElseThrow()
            );
        } finally {
            MDC.remove(RequestCorrelationFilter.MDC_KEY);
        }
    }

    @Test
    void everyAgentRequestUsesTheTwoMinuteTimeoutContract() {
        AgentGatewayService service = new AgentGatewayService(
                "http://127.0.0.1:8000",
                "internal-token",
                null
        );

        HttpRequest request = service.baseRequest("/internal/test")
                .GET()
                .build();

        assertEquals(Duration.ofSeconds(120), request.timeout().orElseThrow());
    }
}
