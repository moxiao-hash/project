package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import com.moxiao.studypilot.learning.infrastructure.TaskChangeEntity;

import java.time.Instant;

public record TaskChangeResponse(
        LearningTaskStatus fromStatus,
        LearningTaskStatus toStatus,
        String reason,
        Instant createdAt
) {
    public static TaskChangeResponse from(TaskChangeEntity entity) {
        return new TaskChangeResponse(
                entity.getFromStatus(),
                entity.getToStatus(),
                entity.getReason(),
                entity.getCreatedAt()
        );
    }
}
