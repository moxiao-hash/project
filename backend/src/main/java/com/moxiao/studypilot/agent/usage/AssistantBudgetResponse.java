package com.moxiao.studypilot.agent.usage;

import java.math.BigDecimal;

/** 预算判定回执；{@code allowed=false} 时只允许纯 Java 查询与导航。 */
public record AssistantBudgetResponse(
        boolean allowed,
        String reason,
        long dailyModelCalls,
        BigDecimal dailyEstimatedCost
) {

    public static AssistantBudgetResponse from(BudgetDecision decision) {
        return new AssistantBudgetResponse(decision.allowed(), decision.reason(),
                decision.dailyModelCalls(), decision.dailyEstimatedCost());
    }
}
