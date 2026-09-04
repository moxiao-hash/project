package com.moxiao.studypilot.agent.tool;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record AgentToolActionResponse(
        String actionId,
        String executionId,
        String toolName,
        int toolVersion,
        AgentToolRiskLevel riskLevel,
        AgentToolActionStatus status,
        String summary,
        JsonNode arguments,
        JsonNode result,
        String error,
        Instant expiresAt
) {
}
