package com.moxiao.studypilot.agent.developer;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 33：界面兜底策略只允许冻结契约中的六个动作与已注册符号目标。
 *
 * <p>本测试先于生产代码编写：迁移前的实现只认识旧别名（OPEN_LOGIN 等）并拒绝一切
 * 非空 target，因此六动作用例在此阶段为 RED。</p>
 */
class InterfaceFallbackPolicyTest {

    private final LocalInterfaceRegistry browserOnly = LocalInterfaceRegistry.forTesting(Map.of());
    private final InterfaceFallbackPolicy policy = new InterfaceFallbackPolicy(browserOnly);

    @Test
    void businessApiAlwaysWinsAndNeverAsksForTheLocalAdapter() {
        InterfaceFallbackDecision decision = policy.preview(
                true, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT");

        assertEquals(InterfaceAutomationChannel.BUSINESS_API, decision.channel());
        assertFalse(decision.fallbackRequired());
        assertEquals("ASSISTANT", decision.targetKey());
    }

    @Test
    void acceptsExactlyTheThreeFrozenBrowserActionsWithRegisteredTargets() {
        assertFallback("PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT");
        assertFallback("PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT_HEALTH");
        assertFallback("PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "WORKSPACE_ARTIFACTS");
        assertFallback("PLAYWRIGHT_DOM", "FOCUS_AGENT_INPUT", "ASSISTANT_INPUT");
        assertFallback("PLAYWRIGHT_DOM", "OPEN_RESULT_PANEL", "WORKSPACE_RESULTS");
    }

    @Test
    void rejectsEveryChannelActionTargetCombinationThatIsNotFullyRegistered() {
        // 通道与动作不匹配
        assertThrows(IllegalArgumentException.class, () -> policy.preview(
                false, "IDEA_ACCESSIBILITY", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT"));
        assertThrows(IllegalArgumentException.class, () -> policy.preview(
                false, "PLAYWRIGHT_DOM", "OPEN_REGISTERED_FILE", "SOURCE_PRIMARY"));
        // 动作与目标不匹配
        assertThrows(IllegalArgumentException.class, () -> policy.preview(
                false, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT_INPUT"));
        assertThrows(IllegalArgumentException.class, () -> policy.preview(
                false, "PLAYWRIGHT_DOM", "FOCUS_AGENT_INPUT", "WORKSPACE_RESULTS"));
        assertThrows(IllegalArgumentException.class, () -> policy.preview(
                false, "PLAYWRIGHT_DOM", "OPEN_RESULT_PANEL", "ASSISTANT"));
    }

    @Test
    void neverRevivesAnyLegacyAliasEvenWhenBusinessApiIsAvailable() {
        for (String legacyAction : Set.of(
                "OPEN_LOGIN", "OPEN_ASSISTANT", "OPEN_ROADMAP", "OPEN_WRONG_QUESTIONS",
                "OPEN_PROJECT_FILE", "OPEN_RUN_CONFIGURATION", "SHOW_TEST_RESULTS")) {
            assertThrows(IllegalArgumentException.class, () -> policy.preview(
                            false, "PLAYWRIGHT_DOM", legacyAction, "ASSISTANT"),
                    "旧别名必须彻底移除: " + legacyAction);
            assertThrows(IllegalArgumentException.class, () -> policy.preview(
                            true, "IDEA_ACCESSIBILITY", legacyAction, "ASSISTANT"),
                    "旧别名不能借业务 API 优先级绕过白名单: " + legacyAction);
        }
    }

    @Test
    void rejectsArbitraryUrlsSelectorsScriptsKeysTextAndPaths() {
        for (String arbitrary : new String[]{
                "https://evil.example.com/steal", "http://127.0.0.1:9200/x", "#login-form",
                "//button[@id='submit']", "document.querySelector('#a')",
                "<script>fetch('/x')</script>", "/Users/moxiao/.ssh/id_rsa",
                "../../.env", "ASSISTANT/../../etc/passwd", "C:\\Windows\\System32",
                "EXAMPLE_USER_MAIN", ""}) {
            assertThrows(IllegalArgumentException.class, () -> policy.preview(
                            false, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", arbitrary),
                    "任意目标必须被拒绝: " + arbitrary);
        }
        assertThrows(IllegalArgumentException.class, () -> policy.preview(
                false, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", null));
    }

    @Test
    void businessApiIsNeverARequestableFallbackChannel() {
        assertThrows(IllegalArgumentException.class, () -> policy.preview(
                false, "BUSINESS_API", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT"));
        assertThrows(IllegalArgumentException.class, () -> policy.preview(
                false, "SHELL", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT"));
        assertThrows(IllegalArgumentException.class, () -> policy.preview(
                false, null, "OPEN_STUDYPILOT_ROUTE", "ASSISTANT"));
    }

    @Test
    void ideActionsFailClosedUntilATrustedConfigurationRegistersAHandle() {
        assertThrows(IllegalArgumentException.class, () -> policy.preview(
                false, "IDEA_ACCESSIBILITY", "OPEN_REGISTERED_FILE", "SOURCE_PRIMARY"));
        assertThrows(IllegalArgumentException.class, () -> policy.preview(
                false, "IDEA_ACCESSIBILITY", "FOCUS_RUN_CONFIGURATION", "RUN_DEFAULT"));
        assertThrows(IllegalArgumentException.class, () -> policy.preview(
                false, "IDEA_ACCESSIBILITY", "SHOW_TEST_RESULT", "TEST_LATEST"));
    }

    @Test
    void registeredIdeTargetsBecomeTheOnlyAllowedIdeTargets() {
        InterfaceFallbackPolicy configured = new InterfaceFallbackPolicy(
                LocalInterfaceRegistry.forTesting(Map.of(
                        LocalInterfaceAction.OPEN_REGISTERED_FILE, Set.of("SOURCE_PRIMARY"),
                        LocalInterfaceAction.FOCUS_RUN_CONFIGURATION, Set.of("RUN_DEFAULT"),
                        LocalInterfaceAction.SHOW_TEST_RESULT, Set.of("TEST_LATEST"))));

        InterfaceFallbackDecision decision = configured.preview(
                false, "IDEA_ACCESSIBILITY", "OPEN_REGISTERED_FILE", "SOURCE_PRIMARY");
        assertEquals(InterfaceAutomationChannel.IDEA_ACCESSIBILITY, decision.channel());
        assertTrue(decision.fallbackRequired());

        assertThrows(IllegalArgumentException.class, () -> configured.preview(
                false, "IDEA_ACCESSIBILITY", "OPEN_REGISTERED_FILE", "/Users/moxiao/secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> configured.preview(
                false, "IDEA_ACCESSIBILITY", "SHOW_TEST_RESULT", "SOURCE_PRIMARY"));
    }

    @Test
    void browserRegistryCannotBeWidenedByConfiguration() {
        LocalInterfaceRegistry widened = LocalInterfaceRegistry.forTesting(Map.of(
                LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, Set.of("ANYWHERE")));

        assertThrows(IllegalArgumentException.class, () -> new InterfaceFallbackPolicy(widened)
                .preview(false, "PLAYWRIGHT_DOM", "OPEN_RESULT_PANEL", "ANYWHERE"));
        assertEquals(InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                new InterfaceFallbackPolicy(widened).preview(
                        false, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT").channel());
    }

    private void assertFallback(String channel, String action, String targetKey) {
        InterfaceFallbackDecision decision = policy.preview(false, channel, action, targetKey);
        assertEquals(InterfaceAutomationChannel.valueOf(channel), decision.channel());
        assertEquals(action, decision.actionKey());
        assertEquals(targetKey, decision.targetKey());
        assertTrue(decision.fallbackRequired());
    }
}
