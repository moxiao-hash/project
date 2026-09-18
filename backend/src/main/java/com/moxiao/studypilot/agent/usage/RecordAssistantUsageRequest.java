package com.moxiao.studypilot.agent.usage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** AI 服务上报模型用量的内部请求；不接受提示词、正文或密钥字段。 */
public record RecordAssistantUsageRequest(
        @NotBlank @Size(max = 36) String usageId,
        @NotBlank @Size(max = 36) String ownerId,
        @NotBlank @Size(max = 36) String conversationId,
        @NotBlank @Size(max = 120) String turnId,
        @Size(max = 36) String executionId,
        @NotBlank @Size(max = 40) String provider,
        @NotBlank @Size(max = 100) String modelName,
        @PositiveOrZero Integer promptTokens,
        @PositiveOrZero Integer cachedPromptTokens,
        @PositiveOrZero Integer completionTokens,
        @PositiveOrZero Integer reasoningTokens,
        @PositiveOrZero Long latencyMs,
        @NotNull Instant occurredAt
) {

    public RecordUsageCommand toCommand() {
        return new RecordUsageCommand(
                usageId, ownerId, conversationId, turnId, executionId, provider,
                new ModelUsage(
                        modelName,
                        promptTokens == null ? 0L : promptTokens,
                        cachedPromptTokens == null ? 0L : cachedPromptTokens,
                        completionTokens == null ? 0L : completionTokens,
                        reasoningTokens,
                        latencyMs == null ? 0L : latencyMs),
                occurredAt);
    }
}
