package com.moxiao.studypilot.agent.automation;

import java.time.Instant;

public record AutomationJobResponse(
        String id,
        String ruleId,
        String ownerId,
        String executionId,
        AutomationRuleType type,
        AutomationJobStatus status,
        Instant scheduledFor,
        String workerId,
        String leaseToken,
        Instant leaseUntil,
        int attempts,
        String resultSummary,
        String errorMessage
) {
    public static AutomationJobResponse from(AssistantAutomationJobEntity entity) {
        return new AutomationJobResponse(
                entity.getId(), entity.getRuleId(), entity.getOwnerId(), entity.getExecutionId(),
                entity.getType(),
                entity.getStatus(), entity.getScheduledFor(), entity.getWorkerId(),
                entity.getLeaseToken(), entity.getLeaseUntil(), entity.getAttempts(),
                entity.getResultSummary(), entity.getErrorMessage()
        );
    }
}
