package com.moxiao.studypilot.agent.api;

import java.math.BigDecimal;

/**
 * 单个模型的调用量、token 明细、估算金额与延迟分位。
 *
 * <p>{@code priceStatus} 为 {@code UNKNOWN} 时 {@code estimatedCost} 必为 {@code null}：
 * 目录里没有该模型价格时不允许用 0 冒充"零成本"。</p>
 */
public record AssistantModelUsageStats(
        String modelName,
        String provider,
        long calls,
        long failedCalls,
        double failureRate,
        long promptTokens,
        long cachedPromptTokens,
        long uncachedPromptTokens,
        long completionTokens,
        long reasoningTokens,
        long totalTokens,
        BigDecimal estimatedCost,
        String currency,
        String priceStatus,
        String priceVersion,
        long p50LatencyMs,
        long p95LatencyMs
) {
}
