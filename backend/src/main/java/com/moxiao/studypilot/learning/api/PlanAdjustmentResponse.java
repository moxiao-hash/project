package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.domain.TriggerType;
import com.moxiao.studypilot.learning.domain.PlanAdjustmentStatus;
import com.moxiao.studypilot.learning.infrastructure.PlanAdjustmentEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;

public record PlanAdjustmentResponse(
        String id,
        String ownerId,
        String planId,
        String idempotencyKey,
        LocalDate analysisDate,
        TriggerType triggerType,
        JsonNode signals,
        String summary,
        JsonNode operations,
        RiskLevel riskLevel,
        PlanAdjustmentStatus status,
        String executionId,
        int beforePlanVersion,
        Integer afterPlanVersion,
        String error,
        Instant createdAt,
        Instant updatedAt
) {
    public static PlanAdjustmentResponse from(
            PlanAdjustmentEntity entity,
            ObjectMapper objectMapper
    ) {
        return new PlanAdjustmentResponse(
                entity.getId(),
                entity.getOwnerId(),
                entity.getPlanId(),
                entity.getIdempotencyKey(),
                entity.getAnalysisDate(),
                entity.getTriggerType(),
                objectMapper.readTree(entity.getSignalsJson()),
                entity.getSummary(),
                objectMapper.readTree(entity.getOperationsJson()),
                entity.getRiskLevel(),
                entity.getStatus(),
                entity.getExecutionId(),
                entity.getBeforePlanVersion(),
                entity.getAfterPlanVersion(),
                entity.getErrorMessage(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
