package com.moxiao.studypilot.agent.usage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** AI 服务在真实 provider 调用前提交的预占请求；不接受提示词、正文或密钥字段。 */
public record ReserveAssistantUsageRequest(
        @NotBlank @Size(max = 36) String usageId,
        @NotBlank @Size(max = 36) String ownerId,
        @NotBlank @Size(max = 36) String conversationId,
        @NotBlank @Size(max = 120) String turnId,
        @NotBlank @Size(max = 40) String purpose,
        @NotBlank @Size(max = 40) String provider,
        @NotBlank @Size(max = 100) String modelName
) {

    public ReserveUsageCommand toCommand() {
        return new ReserveUsageCommand(
                usageId, ownerId, conversationId, turnId, purpose, provider, modelName);
    }
}
