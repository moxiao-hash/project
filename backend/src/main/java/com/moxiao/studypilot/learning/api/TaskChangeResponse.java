package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import com.moxiao.studypilot.learning.infrastructure.TaskChangeEntity;

import java.time.Instant;
import java.time.LocalDate;

public record TaskChangeResponse(
        LearningTaskStatus fromStatus,
        LearningTaskStatus toStatus,
        LocalDate fromScheduledDate,
        LocalDate toScheduledDate,
        String reason,
        Integer actualMinutes,
        Instant createdAt
) {
    public static TaskChangeResponse from(TaskChangeEntity entity) {
        return new TaskChangeResponse(
                entity.getFromStatus(),
                entity.getToStatus(),
                entity.getFromScheduledDate(),
                entity.getToScheduledDate(),
                entity.getReason(),
                entity.getActualMinutes(),
                entity.getCreatedAt()
        );
    }
}
