package com.moxiao.studypilot.agent.usage;

import java.math.BigDecimal;

/**
 * 用量落库结果。
 *
 * <p>{@code estimatedCost} 为 {@code null} 表示价格未知，属于"不可估算"，不是零成本；
 * {@code duplicate} 表示该轮用量此前已记录，本次未重复计费。</p>
 */
public record AssistantUsageRecord(
        String id,
        BigDecimal estimatedCost,
        String currency,
        String priceVersion,
        String priceWindow,
        String priceStatus,
        boolean duplicate
) {

    public static final String PRICE_KNOWN = "KNOWN";
    public static final String PRICE_UNKNOWN = "UNKNOWN";
}
