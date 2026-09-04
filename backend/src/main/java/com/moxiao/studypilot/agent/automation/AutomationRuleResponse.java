package com.moxiao.studypilot.agent.automation;

import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.domain.RiskLevel;

import java.time.Instant;
import java.time.LocalTime;

public record AutomationRuleResponse(
        String id,
        AutomationRuleType type,
        AutomationRuleStatus status,
        String timezone,
        LocalTime localTime,
        RiskLevel riskLevel,
        AgentScope requiredScope,
        Instant createdAt,
        Instant updatedAt
) {
    public static AutomationRuleResponse from(AssistantAutomationRuleEntity entity) {
        return new AutomationRuleResponse(
                entity.getId(),
                entity.getType(),
                entity.getStatus(),
                entity.getTimezone(),
                entity.getLocalTime(),
                entity.getType().riskLevel(),
                entity.getType().requiredScope(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
