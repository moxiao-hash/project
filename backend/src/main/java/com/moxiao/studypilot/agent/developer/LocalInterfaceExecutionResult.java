package com.moxiao.studypilot.agent.developer;

/**
 * Task 33：一次本地界面兜底动作的可验证结果。
 *
 * <p>{@code localAdapterInvoked=false} 只出现在“业务 API 优先”分支；此时
 * {@code channel} 为 {@code BUSINESS_API}，其余回执字段为空。只有真实
 * {@code SUCCEEDED} 回执才能产生该结果，失败与拒绝一律抛异常。</p>
 */
public record LocalInterfaceExecutionResult(
        String channel,
        String actionKey,
        String targetKey,
        boolean localAdapterInvoked,
        String adapter,
        String targetDigest,
        String receiptStatus,
        String startedAt,
        String finishedAt,
        String message
) {

    static LocalInterfaceExecutionResult businessApiPreferred(InterfaceFallbackDecision decision) {
        return new LocalInterfaceExecutionResult(decision.channel().name(), decision.actionKey(),
                decision.targetKey(), false, null, null, null, null, null, decision.reason());
    }

    static LocalInterfaceExecutionResult executed(
            InterfaceFallbackDecision decision, LocalAutomationReceipt receipt
    ) {
        return new LocalInterfaceExecutionResult(decision.channel().name(), receipt.action(),
                decision.targetKey(), true, receipt.adapter(), receipt.targetDigest(),
                receipt.status().name(), receipt.startedAt().toString(),
                receipt.finishedAt().toString(), receipt.message());
    }
}
