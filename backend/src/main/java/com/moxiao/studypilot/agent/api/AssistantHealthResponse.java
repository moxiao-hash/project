package com.moxiao.studypilot.agent.api;

import java.math.BigDecimal;

public record AssistantHealthResponse(
        int totalExecutions,
        int successfulExecutions,
        int failedExecutions,
        double successRate,
        long promptTokens,
        long completionTokens,
        BigDecimal estimatedCost,
        long averageLatencyMs,
        int pendingConfirmations,
        long costSamples,
        long tokenSamples,
        long latencySamples
) {
}
