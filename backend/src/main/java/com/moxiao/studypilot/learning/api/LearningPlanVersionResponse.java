package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.infrastructure.LearningPlanVersionEntity;

import java.time.Instant;

public record LearningPlanVersionResponse(
        int version,
        String snapshotJson,
        String changeReason,
        Instant createdAt
) {
    public static LearningPlanVersionResponse from(LearningPlanVersionEntity entity) {
        return new LearningPlanVersionResponse(
                entity.getVersion(),
                entity.getSnapshotJson(),
                entity.getChangeReason(),
                entity.getCreatedAt()
        );
    }
}
