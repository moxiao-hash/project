package com.moxiao.studypilot.agent.developer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InterfaceFallbackPolicyTest {

    private final InterfaceFallbackPolicy policy = new InterfaceFallbackPolicy();

    @Test
    void alwaysChoosesBusinessApiWhenCapabilityExists() {
        InterfaceFallbackDecision decision = policy.preview(
                true, "PLAYWRIGHT_DOM", "OPEN_LOGIN", null);

        assertEquals(InterfaceAutomationChannel.BUSINESS_API, decision.channel());
        assertEquals(false, decision.fallbackRequired());
    }

    @Test
    void allowsOnlyRegisteredFallbackActionsWithoutArbitraryTargets() {
        InterfaceFallbackDecision browser = policy.preview(
                false, "PLAYWRIGHT_DOM", "OPEN_LOGIN", null);
        InterfaceFallbackDecision idea = policy.preview(
                false, "IDEA_ACCESSIBILITY", "OPEN_PROJECT_FILE", null);

        assertEquals(InterfaceAutomationChannel.PLAYWRIGHT_DOM, browser.channel());
        assertEquals(InterfaceAutomationChannel.IDEA_ACCESSIBILITY, idea.channel());
        assertThrows(IllegalArgumentException.class,
                () -> policy.preview(false, "PLAYWRIGHT_DOM", "CLICK_ANYTHING", "#evil"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.preview(false, "IDEA_ACCESSIBILITY", "OPEN_PROJECT_FILE", "../../.env"));
    }
}
