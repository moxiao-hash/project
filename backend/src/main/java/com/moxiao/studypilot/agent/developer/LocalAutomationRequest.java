package com.moxiao.studypilot.agent.developer;

import java.time.Instant;

/**
 * Task 33：不可变的已签名本地界面请求，字段与冻结契约 §3 一一对应。
 *
 * <p>永远不含 URL、host、port、selector、XPath、JavaScript、HTML、Shell、键码、
 * 自由文本、文件路径、窗口标题或进程 ID；也不含原始 {@code ownerId}。</p>
 *
 * <p>该 record 可以被独立构造，因此所有冻结格式不变量都在此强制：
 * 固定版本、UUID 请求号、小写 sha256 hex 的 ownerHash 与签名、已注册通道/动作组合、
 * 符号目标、base64url 无填充且 ≥128 位的 nonce，以及不倒挂且不超过 60 秒的有效期。</p>
 */
public record LocalAutomationRequest(
        int version,
        String requestId,
        String ownerHash,
        String channel,
        String action,
        String targetKey,
        Instant issuedAt,
        Instant expiresAt,
        String nonce,
        String signature
) {

    public static final int FROZEN_VERSION = 1;

    public LocalAutomationRequest {
        if (version != FROZEN_VERSION) {
            throw new IllegalArgumentException("本地界面请求版本不合法");
        }
        if (requestId == null || !requestId.matches(LocalAutomationSigning.LOWER_UUID)) {
            throw new IllegalArgumentException("本地界面请求 requestId 必须是固定格式小写 UUID");
        }
        if (ownerHash == null || !ownerHash.matches(LocalAutomationSigning.LOWER_HEX_64)) {
            throw new IllegalArgumentException("本地界面请求 ownerHash 必须是小写 sha256 hex");
        }
        if (signature == null || !signature.matches(LocalAutomationSigning.LOWER_HEX_64)) {
            throw new IllegalArgumentException("本地界面请求签名必须是小写 HMAC-SHA256 hex");
        }
        InterfaceAutomationChannel parsedChannel = parseChannel(channel);
        LocalInterfaceAction parsedAction = parseAction(action);
        if (parsedAction.channel() != parsedChannel) {
            throw new IllegalArgumentException("本地界面请求通道与动作不匹配");
        }
        if (!LocalAutomationSigning.isSymbolicTarget(targetKey)) {
            throw new IllegalArgumentException("本地界面请求目标必须已注册符号键");
        }
        LocalAutomationSigning.requireValidNonce(nonce);
        if (issuedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("本地界面请求缺少时间窗");
        }
        if (expiresAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException("本地界面请求有效期倒挂");
        }
        if (expiresAt.isAfter(issuedAt.plusSeconds(60))) {
            throw new IllegalArgumentException("本地界面请求有效期不得超过 60 秒");
        }
    }

    private static InterfaceAutomationChannel parseChannel(String channel) {
        if (channel == null) {
            throw new IllegalArgumentException("本地界面请求缺少通道");
        }
        InterfaceAutomationChannel parsed;
        try {
            parsed = InterfaceAutomationChannel.valueOf(channel);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("本地界面请求通道未注册");
        }
        if (parsed == InterfaceAutomationChannel.BUSINESS_API) {
            throw new IllegalArgumentException("BUSINESS_API 不是可请求的界面兜底通道");
        }
        return parsed;
    }

    private static LocalInterfaceAction parseAction(String action) {
        if (action == null) {
            throw new IllegalArgumentException("本地界面请求缺少动作");
        }
        try {
            return LocalInterfaceAction.valueOf(action);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("本地界面请求动作未注册");
        }
    }
}
