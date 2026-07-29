package com.moxiao.studypilot.agent.application;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentGatewayEnvironmentContractTest {

    @Test
    void springResolvesComposeEnvironmentNameForGatewayProperty() {
        var environment = new SystemEnvironmentPropertySource(
                "compose",
                Map.of(
                        "STUDYPILOT_AI_SERVICE_BASE_URL",
                        "http://ai-service:8000"
                )
        );

        assertEquals(
                "http://ai-service:8000",
                environment.getProperty("studypilot.ai-service-base-url")
        );
    }
}
