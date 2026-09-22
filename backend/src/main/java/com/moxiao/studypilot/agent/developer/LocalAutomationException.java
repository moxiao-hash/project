package com.moxiao.studypilot.agent.developer;

/**
 * Task 33：本地界面适配器的确定性失败。
 *
 * <p>{@code errorCode} 是稳定的机器可读码，{@code message} 必须是可展示给用户、
 * 已清洗且不包含堆栈、路径、URL 或凭据的文本。</p>
 */
public class LocalAutomationException extends RuntimeException {

    private final String errorCode;

    public LocalAutomationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public LocalAutomationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
