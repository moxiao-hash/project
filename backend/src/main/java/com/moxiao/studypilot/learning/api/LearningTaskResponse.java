package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import com.moxiao.studypilot.learning.domain.LearningTaskKind;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskEntity;

import java.time.Instant;
import java.time.LocalDate;

public record LearningTaskResponse(
        String id,
        String planId,
        String title,
        LocalDate scheduledDate,
        int estimatedMinutes,
        LearningTaskStatus status,
        int version,
        Instant completedAt,
        Integer actualMinutes,
        LearningTaskKind taskKind,
        String knowledgePoint,
        String sourceAttemptId
) {
    public static LearningTaskResponse from(LearningTaskEntity entity) {
        return new LearningTaskResponse(
                entity.getId(),
                entity.getPlanId(),
                entity.getTitle(),
                entity.getScheduledDate(),
                entity.getEstimatedMinutes(),
                entity.getStatus(),
                entity.getVersion(),
                entity.getCompletedAt(),
                entity.getActualMinutes(),
                entity.getTaskKind(),
                entity.getKnowledgePoint(),
                entity.getSourceAttemptId()
        );
    }
}
