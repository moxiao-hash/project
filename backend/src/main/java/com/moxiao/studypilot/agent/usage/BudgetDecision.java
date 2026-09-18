package com.moxiao.studypilot.agent.usage;

import java.math.BigDecimal;

/**
 * 模型调用预算判定。
 *
 * <p>{@code allowed=false} 时只允许纯 Java 查询与导航，不发起新的模型调用。</p>
 */
public record BudgetDecision(
        boolean allowed,
        String reason,
        long dailyModelCalls,
        BigDecimal dailyEstimatedCost
) {
}
