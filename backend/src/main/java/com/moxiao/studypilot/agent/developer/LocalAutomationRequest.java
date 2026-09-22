package com.moxiao.studypilot.agent.developer;

import java.time.Instant;

/**
 * Task 33：不可变的已签名本地界面请求，字段与冻结契约 §3 一一对应。
 *
 * <p>永远不含 URL、host、port、selector、XPath、JavaScript、HTML、Shell、键码、
 * 自由文本、文件路径、窗口标题或进程 ID；也不含原始 {@code ownerId}。</p>
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
        requireText(requestId, "requestId");
        requireText(ownerHash, "ownerHash");
        requireText(channel, "channel");
        requireText(action, "action");
        requireText(targetKey, "targetKey");
        requireText(nonce, "nonce");
        requireText(signature, "signature");
        if (issuedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("本地界面请求缺少时间窗");
        }
        if (expiresAt.isAfter(issuedAt.plusSeconds(60))) {
            throw new IllegalArgumentException("本地界面请求有效期不得超过 60 秒");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("本地界面请求缺少字段: " + name);
        }
    }
}
