package com.moxiao.studypilot.agent.tool;

import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

public record AgentToolInvocationRequest(
        @NotBlank String ownerId,
        JsonNode arguments
) {
}
