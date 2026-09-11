package com.moxiao.studypilot.agent.tool;

import tools.jackson.databind.JsonNode;

public record AgentToolDescriptor(
        String name,
        int version,
        String category,
        AgentToolEffect effect,
        AgentToolRiskLevel riskLevel,
        String requiredScope,
        boolean idempotencyRequired,
        JsonNode inputSchema,
        JsonNode outputSchema,
        int timeoutMillis
) {
    public AgentToolDescriptor {
        if (name == null || !name.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
            throw new IllegalArgumentException("工具名称必须是稳定的点分标识");
        }
        if (version < 1 || category == null || effect == null || riskLevel == null
                || inputSchema == null || outputSchema == null) {
            throw new IllegalArgumentException("工具描述不完整");
        }
        if (timeoutMillis < 1_000 || timeoutMillis > 600_000) {
            throw new IllegalArgumentException("工具超时必须在 1 秒到 10 分钟之间");
        }
    }
}
