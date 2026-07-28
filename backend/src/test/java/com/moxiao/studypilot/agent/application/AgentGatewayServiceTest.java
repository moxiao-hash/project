package com.moxiao.studypilot.agent.application;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentGatewayServiceTest {

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
