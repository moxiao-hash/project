package com.moxiao.studypilot.agent.developer;

/**
 * Task 33：受控本地界面适配器客户端。
 *
 * <p>实现必须只使用权限受限的 Unix Domain Socket，绝不开放或使用 TCP/HTTP，
 * 也绝不回退到 {@code ProcessBuilder}、宿主 Shell、AppleScript、任意 Playwright
 * 或键鼠模拟。调用方只能提交冻结枚举与注册符号目标。</p>
 *
 * <p>返回的回执已经过 requestId、adapter、action、targetDigest、枚举、framing、
 * 大小与清洗规则校验；失败一律抛 {@link LocalAutomationException} 失败关闭。</p>
 */
public interface LocalAutomationClient {

    String ERROR_NOT_CONFIGURED = "LOCAL_ADAPTER_NOT_CONFIGURED";
    String ERROR_UNAVAILABLE = "LOCAL_ADAPTER_UNAVAILABLE";
    String ERROR_TIMEOUT = "LOCAL_ADAPTER_TIMEOUT";
    String ERROR_PROTOCOL = "LOCAL_ADAPTER_PROTOCOL_ERROR";
    String ERROR_MISMATCH = "LOCAL_ADAPTER_RESPONSE_MISMATCH";

    LocalAutomationReceipt invoke(
            InterfaceAutomationChannel channel,
            LocalInterfaceAction action,
            String targetKey,
            String ownerId
    );
}
