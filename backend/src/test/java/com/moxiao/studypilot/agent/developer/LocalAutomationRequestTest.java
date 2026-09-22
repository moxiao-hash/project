package com.moxiao.studypilot.agent.developer;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 33：已签名请求记录自身的冻结格式不变量。
 *
 * <p>该 record 可以被独立构造（例如测试、恢复或未来的其它调用方），因此即使绕过
 * {@code UnixSocketLocalAutomationClient} 的构建路径，也必须拒绝过期倒挂、非 UUID 请求号、
 * 非法 ownerHash/签名/通道/动作/符号目标/nonce 等形态。</p>
 */
class LocalAutomationRequestTest {

    private static final String OWNER_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String SIGNATURE = "a".repeat(64);
    private static final String REQUEST_ID = "c28d22db-363d-429a-8c85-618d3632cf4b";
    private static final String NONCE = "dGVzdC1ub25jZS0xMjgtYml0cw";
    private static final Instant ISSUED_AT = Instant.parse("2026-09-22T08:00:00Z");

    @Test
    void acceptsAWellFormedFrozenRequest() {
        assertDoesNotThrow(() -> request(
                LocalAutomationRequest.FROZEN_VERSION, REQUEST_ID, OWNER_HASH,
                "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT",
                ISSUED_AT, ISSUED_AT.plusSeconds(60), NONCE, SIGNATURE));
    }

    @Test
    void rejectsAnyVersionOtherThanTheFrozenOne() {
        assertThrows(IllegalArgumentException.class, () -> request(
                2, REQUEST_ID, OWNER_HASH, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE",
                "ASSISTANT", ISSUED_AT, ISSUED_AT.plusSeconds(60), NONCE, SIGNATURE));
    }

    @Test
    void rejectsExpiryThatPrecedesOrExceedsTheFrozenWindow() {
        assertThrows(IllegalArgumentException.class, () -> request(
                LocalAutomationRequest.FROZEN_VERSION, REQUEST_ID, OWNER_HASH, "PLAYWRIGHT_DOM",
                "OPEN_STUDYPILOT_ROUTE", "ASSISTANT",
                ISSUED_AT, ISSUED_AT.minusSeconds(1), NONCE, SIGNATURE));
        assertThrows(IllegalArgumentException.class, () -> request(
                LocalAutomationRequest.FROZEN_VERSION, REQUEST_ID, OWNER_HASH, "PLAYWRIGHT_DOM",
                "OPEN_STUDYPILOT_ROUTE", "ASSISTANT",
                ISSUED_AT, ISSUED_AT.plusSeconds(61), NONCE, SIGNATURE));
    }

    @Test
    void rejectsMalformedRequestIds() {
        for (String requestId : new String[]{
                "123", "not-a-uuid", "C28D22DB-363D-429A-8C85-618D3632CF4B",
                "c28d22db363d429a8c85618d3632cf4b", "c28d22db-363d-429a-8c85-618d3632cf4"}) {
            assertThrows(IllegalArgumentException.class, () -> request(
                    LocalAutomationRequest.FROZEN_VERSION, requestId, OWNER_HASH,
                    "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT",
                    ISSUED_AT, ISSUED_AT.plusSeconds(60), NONCE, SIGNATURE),
                    "requestId 必须是固定格式 UUID: " + requestId);
        }
    }

    @Test
    void rejectsMalformedOwnerHashAndSignature() {
        for (String ownerHash : new String[]{
                OWNER_HASH.toUpperCase(), "e3b0c442", "z".repeat(64), "e3b0c442".repeat(9)}) {
            assertThrows(IllegalArgumentException.class, () -> request(
                    LocalAutomationRequest.FROZEN_VERSION, REQUEST_ID, ownerHash,
                    "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT",
                    ISSUED_AT, ISSUED_AT.plusSeconds(60), NONCE, SIGNATURE));
        }
        for (String signature : new String[]{
                SIGNATURE.toUpperCase(), "a".repeat(63), "a".repeat(65), "g".repeat(64), "abc"}) {
            assertThrows(IllegalArgumentException.class, () -> request(
                    LocalAutomationRequest.FROZEN_VERSION, REQUEST_ID, OWNER_HASH,
                    "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT",
                    ISSUED_AT, ISSUED_AT.plusSeconds(60), NONCE, signature));
        }
    }

    @Test
    void rejectsUnknownChannelsActionsAndMismatchedCombinations() {
        for (String[] pair : new String[][]{
                {"BUSINESS_API", "OPEN_STUDYPILOT_ROUTE"},
                {"SHELL", "OPEN_STUDYPILOT_ROUTE"},
                {"PLAYWRIGHT_DOM", "OPEN_LOGIN"},
                {"PLAYWRIGHT_DOM", "OPEN_REGISTERED_FILE"},
                {"IDEA_ACCESSIBILITY", "OPEN_STUDYPILOT_ROUTE"},
                {"IDEA_ACCESSIBILITY", "CLICK_ANYTHING"}}) {
            assertThrows(IllegalArgumentException.class, () -> request(
                    LocalAutomationRequest.FROZEN_VERSION, REQUEST_ID, OWNER_HASH,
                    pair[0], pair[1], "ASSISTANT",
                    ISSUED_AT, ISSUED_AT.plusSeconds(60), NONCE, SIGNATURE));
        }
    }

    @Test
    void rejectsAnyTargetThatIsNotARegisteredSymbolicKey() {
        for (String targetKey : new String[]{
                "assistant", "/workspaces", "https://evil.example.com", "#input",
                "//button[@id='x']", "../.env", "A/../../etc/passwd", "A B", ""}) {
            assertThrows(IllegalArgumentException.class, () -> request(
                    LocalAutomationRequest.FROZEN_VERSION, REQUEST_ID, OWNER_HASH,
                    "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", targetKey,
                    ISSUED_AT, ISSUED_AT.plusSeconds(60), NONCE, SIGNATURE),
                    "目标必须是符号键: " + targetKey);
        }
    }

    @Test
    void rejectsNoncesOutsideTheFrozenBase64Url128BitRule() {
        for (String nonce : new String[]{
                NONCE + "==", "AAAA+AAAAA/AAAAAAAAAAAA", "AAAA", "not base64url", "", "a".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> request(
                    LocalAutomationRequest.FROZEN_VERSION, REQUEST_ID, OWNER_HASH,
                    "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT",
                    ISSUED_AT, ISSUED_AT.plusSeconds(60), nonce, SIGNATURE));
        }
    }

    @Test
    void exposesTheFrozenFieldSetWithoutAnyGenericControlField() {
        LocalAutomationRequest request = request(
                LocalAutomationRequest.FROZEN_VERSION, REQUEST_ID, OWNER_HASH, "PLAYWRIGHT_DOM",
                "OPEN_STUDYPILOT_ROUTE", "ASSISTANT", ISSUED_AT, ISSUED_AT.plusSeconds(60),
                NONCE, SIGNATURE);

        assertEquals(java.util.Set.of("version", "requestId", "ownerHash", "channel", "action",
                        "targetKey", "issuedAt", "expiresAt", "nonce", "signature"),
                java.util.Arrays.stream(LocalAutomationRequest.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals("ASSISTANT", request.targetKey());
    }

    private static LocalAutomationRequest request(
            int version, String requestId, String ownerHash, String channel, String action,
            String targetKey, Instant issuedAt, Instant expiresAt, String nonce, String signature
    ) {
        return new LocalAutomationRequest(version, requestId, ownerHash, channel, action,
                targetKey, issuedAt, expiresAt, nonce, signature);
    }
}
