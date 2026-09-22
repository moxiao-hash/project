package com.moxiao.studypilot.agent.developer;

/**
 * Task 33：界面兜底决策。
 *
 * <p>{@code channel} 为 {@code BUSINESS_API} 时表示存在确定性业务 API，
 * {@code fallbackRequired} 必须为 {@code false}，调用方不得触碰本地适配器。</p>
 */
public record InterfaceFallbackDecision(
        InterfaceAutomationChannel channel,
        String actionKey,
        String targetKey,
        boolean fallbackRequired,
        String reason
) { }
