package com.moxiao.studypilot.agent.developer;

public record InterfaceFallbackDecision(
        InterfaceAutomationChannel channel,
        String actionKey,
        boolean fallbackRequired,
        String reason
) { }
