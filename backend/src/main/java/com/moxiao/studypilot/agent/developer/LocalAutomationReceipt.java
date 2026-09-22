package com.moxiao.studypilot.agent.developer;

import java.time.Instant;

/**
 * Task 33：不可变的已验证本地界面回执，字段与冻结契约 §5 一一对应。
 *
 * <p>绝不含屏幕截图、DOM、窗口标题、文件路径、URL、用户输入、堆栈、密钥或页面正文。
 * {@code errorCode} 仅在失败/拒绝时可能出现；{@code message} 最多 200 字符。</p>
 */
public record LocalAutomationReceipt(
        int version,
        String requestId,
        String adapter,
        String action,
        String targetDigest,
        Instant startedAt,
        Instant finishedAt,
        LocalAutomationStatus status,
        String errorCode,
        String message
) {

    public static final int MAX_MESSAGE_CHARS = 200;

    public LocalAutomationReceipt {
        if (version != LocalAutomationRequest.FROZEN_VERSION) {
            throw new IllegalArgumentException("本地界面回执版本不合法");
        }
        requireText(requestId, "requestId");
        requireText(adapter, "adapter");
        requireText(action, "action");
        requireText(targetDigest, "targetDigest");
        requireText(message, "message");
        if (startedAt == null || finishedAt == null) {
            throw new IllegalArgumentException("本地界面回执缺少时间戳");
        }
        if (status == null) {
            throw new IllegalArgumentException("本地界面回执缺少状态");
        }
        if (message.length() > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException("本地界面回执消息超过安全上限");
        }
        if (status == LocalAutomationStatus.SUCCEEDED && errorCode != null) {
            throw new IllegalArgumentException("成功的本地界面回执不得携带错误码");
        }
    }

    public boolean succeeded() {
        return status == LocalAutomationStatus.SUCCEEDED;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("本地界面回执缺少字段: " + name);
        }
    }
}
