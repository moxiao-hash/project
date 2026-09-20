package com.moxiao.studypilot.agent.usage;

import java.math.BigDecimal;

/**
 * 用量上报回执。
 *
 * <p>{@code estimatedCost} 为 {@code null} 表示价格未知（"不可估算"），不是零成本；
 * {@code priceStatus} 明确区分 {@code KNOWN} 与 {@code UNKNOWN}。</p>
 */
public record AssistantUsageResponse(
        String usageId,
        BigDecimal estimatedCost,
        String currency,
        String priceVersion,
        String priceWindow,
        String priceStatus,
        boolean duplicate
) {

    public static AssistantUsageResponse from(AssistantUsageRecord record) {
        return new AssistantUsageResponse(record.id(), record.estimatedCost(),
                record.currency(), record.priceVersion(), record.priceWindow(),
                record.priceStatus(), record.duplicate());
    }
}
