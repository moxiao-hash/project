package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.domain.LearningPlanStatus;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanEntity;

import java.time.LocalDate;

public record LearningPlanResponse(
        String id,
        String goalId,
        String title,
        LocalDate startDate,
        LocalDate endDate,
        LearningPlanStatus status,
        int version
) {
    public static LearningPlanResponse from(LearningPlanEntity entity) {
        return new LearningPlanResponse(
                entity.getId(),
                entity.getGoalId(),
                entity.getTitle(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getStatus(),
                entity.getVersion()
        );
    }
}
