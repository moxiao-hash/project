package com.moxiao.studypilot.agent.developer;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class InterfaceFallbackPolicy {
    private static final Map<InterfaceAutomationChannel, Set<String>> ALLOWED_ACTIONS = Map.of(
            InterfaceAutomationChannel.PLAYWRIGHT_DOM,
            Set.of("OPEN_LOGIN", "OPEN_ASSISTANT", "OPEN_ROADMAP", "OPEN_WRONG_QUESTIONS"),
            InterfaceAutomationChannel.IDEA_ACCESSIBILITY,
            Set.of("OPEN_PROJECT_FILE", "OPEN_RUN_CONFIGURATION", "SHOW_TEST_RESULTS"));

    public InterfaceFallbackDecision preview(
            boolean businessApiAvailable,
            String requestedChannel,
            String actionKey,
            String arbitraryTarget
    ) {
        if (businessApiAvailable) {
            return new InterfaceFallbackDecision(InterfaceAutomationChannel.BUSINESS_API,
                    actionKey, false, "存在确定性业务 API，禁止使用界面模拟操作");
        }
        if (arbitraryTarget != null && !arbitraryTarget.isBlank()) {
            throw new IllegalArgumentException("界面兜底不接受任意 URL、选择器、路径或键鼠参数");
        }
        InterfaceAutomationChannel channel;
        try {
            channel = InterfaceAutomationChannel.valueOf(requestedChannel);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("未注册的界面兜底通道");
        }
        if (channel == InterfaceAutomationChannel.BUSINESS_API
                || !ALLOWED_ACTIONS.getOrDefault(channel, Set.of()).contains(actionKey)) {
            throw new IllegalArgumentException("未注册的界面兜底动作");
        }
        return new InterfaceFallbackDecision(channel, actionKey, true,
                "仅在能力目录确认没有业务 API 时允许执行白名单动作");
    }
}
