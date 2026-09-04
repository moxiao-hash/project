package com.moxiao.studypilot.agent.tool;

import tools.jackson.databind.JsonNode;

public record AgentToolInvocationResponse(
        String toolName,
        int toolVersion,
        JsonNode data,
        boolean truncated
) {
}
