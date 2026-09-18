package com.moxiao.studypilot.agent.usage;

import java.time.Instant;

/** 上报一次模型调用用量；幂等键是 {@code usageId}（每次模型调用唯一）。 */
public record RecordUsageCommand(
        String usageId,
        String ownerId,
        String conversationId,
        String turnId,
        String executionId,
        String provider,
        ModelUsage usage,
        Instant occurredAt
) {
}
