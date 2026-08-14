package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapQuizGenerationJobEntity;

import java.time.Instant;

public record RoadmapQuizGenerationResponse(
        String jobId,
        String purpose,
        String status,
        int retrySequence,
        int attemptCount,
        String quizId,
        String lastError,
        Instant leaseUntil,
        Instant updatedAt
) {
    public static RoadmapQuizGenerationResponse from(RoadmapQuizGenerationJobEntity job) {
        return new RoadmapQuizGenerationResponse(
                job.getId(), job.getPurpose().name(), job.getStatus().name(),
                job.getRetrySequence(), job.getAttemptCount(), job.getQuizId(),
                job.getLastError(), job.getLeaseUntil(), job.getUpdatedAt());
    }
}
