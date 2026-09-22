package com.moxiao.studypilot.agent.developer;

import org.springframework.stereotype.Component;

/**
 * Task 33：界面兜底策略。
 *
 * <p>只允许冻结契约 §4 的六个动作与已登记符号目标；旧动作别名（OPEN_LOGIN、
 * OPEN_PROJECT_FILE 等）已彻底移除，不能借业务 API 优先级绕过白名单。
 * 校验顺序是刻意的失败关闭：先确认 channel/action/targetKey 完整命中静态注册表，
 * 再应用“业务 API 优先”，因此任何未注册组合都不会因为 {@code businessApiAvailable=true}
 * 而被放过，也不会产生任何界面副作用。</p>
 */
@Component
public class InterfaceFallbackPolicy {

    private final LocalInterfaceRegistry registry;

    public InterfaceFallbackPolicy(LocalInterfaceRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param businessApiAvailable 能力目录给出的业务 API 可用性
     * @param requestedChannel     只能是 {@code PLAYWRIGHT_DOM} 或 {@code IDEA_ACCESSIBILITY}
     * @param actionKey            必须是六个冻结动作之一
     * @param targetKey            注册表中的符号目标；绝不是 URL、选择器、路径或文本
     */
    public InterfaceFallbackDecision preview(
            boolean businessApiAvailable,
            String requestedChannel,
            String actionKey,
            String targetKey
    ) {
        InterfaceAutomationChannel channel = parseChannel(requestedChannel);
        LocalInterfaceAction action = parseAction(actionKey);
        if (action.channel() != channel) {
            throw new IllegalArgumentException("通道与动作不匹配，界面兜底失败关闭");
        }
        if (!registry.isRegistered(channel, action, targetKey)) {
            throw new IllegalArgumentException("未注册的界面兜底目标，失败关闭");
        }
        if (businessApiAvailable) {
            return new InterfaceFallbackDecision(InterfaceAutomationChannel.BUSINESS_API,
                    action.name(), targetKey, false, "存在确定性业务 API，禁止使用界面模拟操作");
        }
        return new InterfaceFallbackDecision(channel, action.name(), targetKey, true,
                "仅在能力目录确认没有业务 API 时允许执行白名单动作");
    }

    private static InterfaceAutomationChannel parseChannel(String requestedChannel) {
        if (requestedChannel == null || requestedChannel.isBlank()) {
            throw new IllegalArgumentException("未注册的界面兜底通道");
        }
        InterfaceAutomationChannel channel;
        try {
            channel = InterfaceAutomationChannel.valueOf(requestedChannel);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未注册的界面兜底通道");
        }
        if (channel == InterfaceAutomationChannel.BUSINESS_API) {
            throw new IllegalArgumentException("BUSINESS_API 不是可请求的界面兜底通道");
        }
        return channel;
    }

    private static LocalInterfaceAction parseAction(String actionKey) {
        if (actionKey == null || actionKey.isBlank()) {
            throw new IllegalArgumentException("未注册的界面兜底动作");
        }
        try {
            return LocalInterfaceAction.valueOf(actionKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未注册的界面兜底动作");
        }
    }
}
