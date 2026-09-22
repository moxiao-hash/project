package com.moxiao.studypilot.agent.developer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Task 33：本地界面适配器的签名与摘要规范化。
 *
 * <p>长度前缀约定：每个字段编码为 {@code UTF-8 字节长度 + "#" + 值}，按冻结契约的固定顺序
 * 直接拼接，再以 UTF-8 计算 HMAC-SHA256。该编码已与 ZCode 交付的本地服务
 * （{@code calculateCanonicalPayload} 使用 {@code Buffer.byteLength(field, 'utf8')}）逐字节对齐，
 * 并由 {@code LocalAutomationSigningTest} 中固化的跨端确定性向量锁死。</p>
 *
 * <p>该规范化只覆盖冻结契约的九个字段及 {@code signature} 自身之外的顺序要求；
 * 任何字段、顺序或编码的变更都必须先由 Codex 冻结。</p>
 */
public final class LocalAutomationSigning {

    /** 仅供本地开发占位；生产必须通过环境/配置注入独立密钥。 */
    public static final String DOCUMENTED_DEFAULT_SECRET =
            "studypilot-local-automation-default-secret-32b";
    public static final int MIN_SECRET_BYTES = 32;
    public static final int NONCE_BYTES = 16;
    /** 冻结契约：符号目标只能是安全的大写标识，绝不是路径、URL 或选择器。 */
    public static final String SYMBOLIC_TARGET = "[A-Z][A-Z0-9_]{0,63}";
    /** 冻结契约：requestId 是小写 UUID；ownerHash/signature/targetDigest 是小写 sha256 hex。 */
    public static final String LOWER_UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public static final String LOWER_HEX_64 = "[0-9a-f]{64}";
    private static final String BASE64URL_NO_PADDING = "[A-Za-z0-9_-]+";
    private static final int MAX_NONCE_CHARS = 128;

    private static final String HMAC_SHA256 = "HmacSHA256";

    private LocalAutomationSigning() {
    }

    /** 签名覆盖的九个字段，顺序与冻结契约完全一致。 */
    public static String canonicalRequestPayload(
            int version,
            String requestId,
            String ownerHash,
            String channel,
            String action,
            String targetKey,
            Instant issuedAt,
            Instant expiresAt,
            String nonce
    ) {
        StringBuilder payload = new StringBuilder();
        append(payload, Integer.toString(version));
        append(payload, requestId);
        append(payload, ownerHash);
        append(payload, channel);
        append(payload, action);
        append(payload, targetKey);
        append(payload, issuedAt == null ? null : issuedAt.toString());
        append(payload, expiresAt == null ? null : expiresAt.toString());
        append(payload, nonce);
        return payload.toString();
    }

    public static String signature(String signingSecret, String canonicalPayload) {
        requireUsableSecret(signingSecret);
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] hash = mac.doFinal(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算本地界面适配器签名", exception);
        }
    }

    /** 只发送 {@code sha256(ownerId)}，绝不发送原始用户标识。 */
    public static String ownerHash(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId 不能为空");
        }
        return sha256Hex(ownerId);
    }

    /** 回执里的目标摘要：冻结契约要求 sha256(channel/action/targetKey) 小写 hex。 */
    public static String targetDigest(String channel, String action, String targetKey) {
        return sha256Hex(channel + "/" + action + "/" + targetKey);
    }

    public static String newNonce(SecureRandom secureRandom) {
        byte[] bytes = new byte[NONCE_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 独立密钥必须是至少 32 字节且不是文档占位值。
     *
     * @throws IllegalStateException 密钥缺失、过短或仍是占位值
     */
    public static void requireUsableSecret(String signingSecret) {
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalStateException("必须配置本地界面适配器独立签名密钥");
        }
        if (DOCUMENTED_DEFAULT_SECRET.equals(signingSecret)) {
            throw new IllegalStateException("本地界面适配器不能使用文档占位签名密钥");
        }
        if (signingSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("本地界面适配器签名密钥不得少于 32 字节");
        }
    }

    static boolean isSymbolicTarget(String targetKey) {
        return targetKey != null && targetKey.matches(SYMBOLIC_TARGET);
    }

    /**
     * 冻结契约的 nonce 规则：base64url 无填充，且解码后不少于 128 位。
     *
     * <p>无论 nonce 来自内部 {@link SecureRandom} 还是注入的生成器，都必须通过同一校验，
     * 避免“测试注入更弱 nonce”成为绕过冻结规则的口子。</p>
     *
     * @throws IllegalArgumentException nonce 为空、含填充/非 base64url 字符、过长或不足 128 位
     */
    public static void requireValidNonce(String nonce) {
        if (!isValidNonce(nonce)) {
            throw new IllegalArgumentException(
                    "本地界面 nonce 必须是 base64url 无填充且至少 128 位");
        }
    }

    public static boolean isValidNonce(String nonce) {
        if (nonce == null || nonce.isEmpty() || nonce.length() > MAX_NONCE_CHARS) {
            return false;
        }
        if (!nonce.matches(BASE64URL_NO_PADDING)) {
            return false;
        }
        try {
            return Base64.getUrlDecoder().decode(nonce).length >= NONCE_BYTES;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        // 与 ZCode 的 `Buffer.byteLength(field, 'utf8')` 逐字节一致。
        target.append(safe.getBytes(StandardCharsets.UTF_8).length).append('#').append(safe);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算摘要", exception);
        }
    }
}
