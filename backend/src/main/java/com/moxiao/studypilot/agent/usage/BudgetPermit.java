package com.moxiao.studypilot.agent.usage;

import java.time.Instant;

/** 模型调用预占许可。{@code allowed=false} 时 AI 侧必须放弃模型调用并回落到纯 Java 路径。 */
public record BudgetPermit(
        String reservationId,
        boolean allowed,
        String reason,
        Integer maxOutputTokensPerTurn,
        String timezone,
        Instant expiresAt
) {
}
