package com.moxiao.studypilot.agent.developer;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 33：签名与摘要规范化必须与冻结契约逐字段一致，且与 Runner 现有
 * {@code len#value} 长度前缀约定同族。
 */
class LocalAutomationSigningTest {

    private static final String SECRET = "task-33-local-automation-test-secret-key";
    private static final String OWNER_HASH = "ab".repeat(32);
    private static final String REQUEST_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final Instant ISSUED_AT = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-22T00:01:00Z");

    /** 与冻结契约签名顺序完全一致的独立期望值（硬编码，不复用生产拼接代码）。 */
    private static final String EXPECTED_PAYLOAD =
            "1#1"
                    + "36#0f8fad5b-d9cb-469f-a165-70867728950e"
                    + "64#" + "ab".repeat(32)
                    + "14#PLAYWRIGHT_DOM"
                    + "21#OPEN_STUDYPILOT_ROUTE"
                    + "9#ASSISTANT"
                    + "20#2026-09-22T00:00:00Z"
                    + "20#2026-09-22T00:01:00Z"
                    + "22#AAAAAAAAAAAAAAAAAAAAAA";

    @Test
    void canonicalPayloadUsesTheFrozenFieldOrderWithLengthPrefixes() {
        assertEquals(EXPECTED_PAYLOAD, LocalAutomationSigning.canonicalRequestPayload(
                1, REQUEST_ID, OWNER_HASH, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE",
                "ASSISTANT", ISSUED_AT, EXPIRES_AT, NONCE));
    }

    @Test
    void signatureIsLowercaseHexHmacSha256OverTheCanonicalPayload() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(
                mac.doFinal(EXPECTED_PAYLOAD.getBytes(StandardCharsets.UTF_8)));

        String signature = LocalAutomationSigning.signature(SECRET, EXPECTED_PAYLOAD);

        assertEquals(expected, signature);
        assertEquals(signature.toLowerCase(), signature);
        assertEquals(64, signature.length());
    }

    @Test
    void everySignedFieldChangesTheSignature() {
        String baseline = LocalAutomationSigning.signature(SECRET, EXPECTED_PAYLOAD);
        Set<String> variants = new HashSet<>();
        variants.add(LocalAutomationSigning.canonicalRequestPayload(
                2, REQUEST_ID, OWNER_HASH, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE",
                "ASSISTANT", ISSUED_AT, EXPIRES_AT, NONCE));
        variants.add(LocalAutomationSigning.canonicalRequestPayload(
                1, "11111111-1111-1111-1111-111111111111", OWNER_HASH, "PLAYWRIGHT_DOM",
                "OPEN_STUDYPILOT_ROUTE", "ASSISTANT", ISSUED_AT, EXPIRES_AT, NONCE));
        variants.add(LocalAutomationSigning.canonicalRequestPayload(
                1, REQUEST_ID, "cd".repeat(32), "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE",
                "ASSISTANT", ISSUED_AT, EXPIRES_AT, NONCE));
        variants.add(LocalAutomationSigning.canonicalRequestPayload(
                1, REQUEST_ID, OWNER_HASH, "IDEA_ACCESSIBILITY", "OPEN_STUDYPILOT_ROUTE",
                "ASSISTANT", ISSUED_AT, EXPIRES_AT, NONCE));
        variants.add(LocalAutomationSigning.canonicalRequestPayload(
                1, REQUEST_ID, OWNER_HASH, "PLAYWRIGHT_DOM", "FOCUS_AGENT_INPUT",
                "ASSISTANT", ISSUED_AT, EXPIRES_AT, NONCE));
        variants.add(LocalAutomationSigning.canonicalRequestPayload(
                1, REQUEST_ID, OWNER_HASH, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE",
                "ASSISTANT_HEALTH", ISSUED_AT, EXPIRES_AT, NONCE));
        variants.add(LocalAutomationSigning.canonicalRequestPayload(
                1, REQUEST_ID, OWNER_HASH, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE",
                "ASSISTANT", ISSUED_AT.plusSeconds(1), EXPIRES_AT, NONCE));
        variants.add(LocalAutomationSigning.canonicalRequestPayload(
                1, REQUEST_ID, OWNER_HASH, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE",
                "ASSISTANT", ISSUED_AT, EXPIRES_AT.plusSeconds(1), NONCE));
        variants.add(LocalAutomationSigning.canonicalRequestPayload(
                1, REQUEST_ID, OWNER_HASH, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE",
                "ASSISTANT", ISSUED_AT, EXPIRES_AT, "BBBBBBBBBBBBBBBBBBBBBB"));

        assertEquals(9, variants.size());
        for (String payload : variants) {
            assertNotEquals(baseline, LocalAutomationSigning.signature(SECRET, payload));
        }
    }

    @Test
    void signatureDependsOnTheIndependentSecret() {
        assertNotEquals(
                LocalAutomationSigning.signature(SECRET, EXPECTED_PAYLOAD),
                LocalAutomationSigning.signature(SECRET + "-other", EXPECTED_PAYLOAD));
    }

    @Test
    void rejectsShortPlaceholderOrBlankSecrets() {
        for (String secret : new String[]{
                null, "", "   ", "short-secret", "a".repeat(31),
                LocalAutomationSigning.DOCUMENTED_DEFAULT_SECRET}) {
            assertThrows(IllegalStateException.class,
                    () -> LocalAutomationSigning.requireUsableSecret(secret),
                    "不安全的签名密钥必须被拒绝");
        }
        LocalAutomationSigning.requireUsableSecret("b".repeat(32));
    }

    @Test
    void ownerHashIsLowercaseSha256HexOfOwnerId() {
        String ownerHash = LocalAutomationSigning.ownerHash("user-123");

        assertEquals(64, ownerHash.length());
        assertEquals(ownerHash.toLowerCase(), ownerHash);
        assertEquals(ownerHash, LocalAutomationSigning.ownerHash("user-123"));
        assertNotEquals(ownerHash, LocalAutomationSigning.ownerHash("user-124"));
    }

    @Test
    void targetDigestIsStableAndCombinationSpecific() {
        String digest = LocalAutomationSigning.targetDigest(
                "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT");

        assertEquals(64, digest.length());
        assertEquals(digest.toLowerCase(), digest);
        assertEquals(digest, LocalAutomationSigning.targetDigest(
                "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT"));
        assertNotEquals(digest, LocalAutomationSigning.targetDigest(
                "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT_HEALTH"));
        assertNotEquals(digest, LocalAutomationSigning.targetDigest(
                "PLAYWRIGHT_DOM", "FOCUS_AGENT_INPUT", "ASSISTANT"));
    }

    @Test
    void matchesZcodeDeliveredCrossLanguageVectorsByteForByte() {
        // 向量来源：ZCode 交付的 local-automation-service/tests/vectors.spec.ts 与
        // docs/verification/task-33-local-service.md §4（本分支只读引用，未修改对齐方文件）。
        String zcodeSecret = "0123456789abcdef0123456789abcdef";
        String zcodePayload = LocalAutomationSigning.canonicalRequestPayload(
                1,
                "c28d22db-363d-429a-8c85-618d3632cf4b",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT",
                Instant.parse("2026-09-22T08:00:00Z"),
                Instant.parse("2026-09-22T08:01:00Z"),
                "dGVzdC1ub25jZS0xMjgtYml0cw");

        assertEquals(
                "1#1"
                        + "36#c28d22db-363d-429a-8c85-618d3632cf4b"
                        + "64#e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                        + "14#PLAYWRIGHT_DOM"
                        + "21#OPEN_STUDYPILOT_ROUTE"
                        + "9#ASSISTANT"
                        + "20#2026-09-22T08:00:00Z"
                        + "20#2026-09-22T08:01:00Z"
                        + "26#dGVzdC1ub25jZS0xMjgtYml0cw",
                zcodePayload, "Java 规范化载荷必须与 ZCode 逐字节一致");
        assertEquals("e471c52c3d1ee78f52a0178f1ec2e0e09eac84a1354ce45141569336c7fdc943",
                LocalAutomationSigning.signature(zcodeSecret, zcodePayload),
                "Java HMAC 必须与 ZCode 期望值一致");
        assertEquals("d30b1c64274f91e571851b4d15914d7c8f7cb917c0689090be571279bbd9f0eb",
                LocalAutomationSigning.targetDigest(
                        "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT"),
                "浏览器 targetDigest 必须与 ZCode 期望值一致");
        assertEquals("6691497224c600de89d55e73b2cc86431d6a090892d81e80ecdf11787c36171e",
                LocalAutomationSigning.targetDigest(
                        "IDEA_ACCESSIBILITY", "OPEN_REGISTERED_FILE", "FILE_SAMPLE"));
    }

    @Test
    void targetDigestUsesTheLiteralSlashJoinedForm() throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        String expected = HexFormat.of().formatHex(digest.digest(
                "PLAYWRIGHT_DOM/OPEN_STUDYPILOT_ROUTE/ASSISTANT".getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, LocalAutomationSigning.targetDigest(
                "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT"));
    }

    @Test
    void nonceIsBase64UrlWithoutPaddingAndAtLeast128Bits() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < 64; index++) {
            String nonce = LocalAutomationSigning.newNonce(random);
            assertTrue(nonce.matches("[A-Za-z0-9_-]{22,}"), "nonce 必须是 base64url 无填充: " + nonce);
            assertEquals(-1, nonce.indexOf('='));
            assertTrue(seen.add(nonce), "nonce 必须不可预测且唯一");
        }
    }
}
