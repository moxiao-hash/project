package com.moxiao.studypilot.aicredential.application;

/**
 * 凭据加解密失败。消息刻意保持笼统，避免把密钥、密文或底层密码学细节写入响应和日志。
 */
public class AiCredentialSecurityException extends IllegalStateException {

    public AiCredentialSecurityException(String message) {
        super(message);
    }

    public AiCredentialSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
