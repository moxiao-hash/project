package com.moxiao.studypilot.agent.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 已认证用户自己的助手健康指标。
 *
 * <p>执行层字段来自 Agent 执行记录；{@code usage*} 与 {@code models} 来自真实模型
 * 调用用量。金额一律 {@link BigDecimal}；未知价格用 {@code priceStatus=UNKNOWN}
 * 与 {@code null} 金额表达，绝不相加为 0。全部查询都限定在调用者 owner 下。</p>
 */
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
        long latencySamples,
        long modelCalls,
        long failedModelCalls,
        double modelFailureRate,
        long modelPromptTokens,
        long modelCachedPromptTokens,
        long modelUncachedPromptTokens,
        long modelCompletionTokens,
        long modelReasoningTokens,
        long modelTotalTokens,
        long unknownPriceCalls,
        BigDecimal usageEstimatedCost,
        String currency,
        String priceStatus,
        String priceVersion,
        long p50LatencyMs,
        long p95LatencyMs,
        List<AssistantModelUsageStats> models
) {
}
