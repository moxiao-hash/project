package com.moxiao.studypilot.course.api;

import com.moxiao.studypilot.course.domain.LessonProgressStatus;
import com.moxiao.studypilot.course.infrastructure.LessonProgressEntity;

import java.time.Instant;

public record LessonProgressResponse(
        LessonProgressStatus status,
        boolean videoCompleted,
        boolean readingCompleted,
        boolean practiceCompleted,
        boolean checkpointPassed,
        boolean quizPassed,
        String lastSectionKey,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt
) {
    public static LessonProgressResponse notStarted() {
        return new LessonProgressResponse(
                LessonProgressStatus.NOT_STARTED,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                null,
                null
        );
    }

    public static LessonProgressResponse from(LessonProgressEntity entity) {
        return new LessonProgressResponse(
                entity.getStatus(),
                entity.isVideoCompleted(),
                entity.isReadingCompleted(),
                entity.isPracticeCompleted(),
                entity.isCheckpointPassed(),
                entity.isQuizPassed(),
                entity.getLastSectionKey(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getUpdatedAt()
        );
    }
}
