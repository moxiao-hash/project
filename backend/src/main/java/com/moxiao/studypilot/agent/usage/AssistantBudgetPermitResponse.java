package com.moxiao.studypilot.agent.usage;

import java.time.Instant;

/** 预占许可回执；{@code allowed=false} 时 AI 侧只允许纯 Java 查询与导航。 */
public record AssistantBudgetPermitResponse(
        String reservationId,
        boolean allowed,
        String reason,
        Integer maxOutputTokensPerTurn,
        String timezone,
        Instant expiresAt
) {

    public static AssistantBudgetPermitResponse from(BudgetPermit permit) {
        return new AssistantBudgetPermitResponse(
                permit.reservationId(),
                permit.allowed(),
                permit.reason(),
                permit.maxOutputTokensPerTurn(),
                permit.timezone(),
                permit.expiresAt());
    }
}
