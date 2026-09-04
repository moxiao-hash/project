package com.moxiao.studypilot.agent.tool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record AgentToolInvocationRequest(
        @NotBlank String ownerId,
        @Size(max = 180) String idempotencyKey,
        JsonNode arguments
) {
}
