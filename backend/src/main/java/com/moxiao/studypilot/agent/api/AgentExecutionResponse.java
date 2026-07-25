package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.domain.ExecutionType;
import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.domain.TriggerType;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionEntity;

import java.math.BigDecimal;
import java.time.Instant;

public record AgentExecutionResponse(
        String id,
        String idempotencyKey,
        ExecutionType executionType,
        TriggerType triggerType,
        RiskLevel riskLevel,
        AgentScope requiredScope,
        ExecutionStatus status,
        String summary,
        String resultSummary,
        String errorMessage,
        String modelName,
        Integer promptTokens,
        Integer completionTokens,
        Long latencyMs,
        BigDecimal estimatedCost,
        Instant createdAt
) {
    public static AgentExecutionResponse from(AgentExecutionEntity entity) {
        return new AgentExecutionResponse(
                entity.getId(),
                entity.getIdempotencyKey(),
                entity.getExecutionType(),
                entity.getTriggerType(),
                entity.getRiskLevel(),
                entity.getRequiredScope(),
                entity.getStatus(),
                entity.getSummary(),
                entity.getResultSummary(),
                entity.getErrorMessage(),
                entity.getModelName(),
                entity.getPromptTokens(),
                entity.getCompletionTokens(),
                entity.getLatencyMs(),
                entity.getEstimatedCost(),
                entity.getCreatedAt()
        );
    }
}
