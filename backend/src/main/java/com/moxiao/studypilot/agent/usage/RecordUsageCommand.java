package com.moxiao.studypilot.agent.usage;

import java.time.Instant;

/** 上报一次模型调用用量；幂等键为 {@code (executionId, turnId)}。 */
public record RecordUsageCommand(
        String ownerId,
        String executionId,
        String turnId,
        String provider,
        ModelUsage usage,
        Instant occurredAt
) {
}
