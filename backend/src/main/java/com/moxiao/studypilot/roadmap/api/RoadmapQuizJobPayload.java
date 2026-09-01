package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeCheckInEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapQuizGenerationJobEntity;

import java.time.Instant;

public record RoadmapQuizJobPayload(
        String id, String ownerId, String nodeId, String checkInId,
        String checkInSummary, String purpose, String status, int retrySequence,
        int attemptCount, String leaseToken, String quizId, String lastError, Instant leaseUntil
) {
    public static RoadmapQuizJobPayload from(
            RoadmapQuizGenerationJobEntity job, RoadmapNodeCheckInEntity checkIn
    ) {
        return new RoadmapQuizJobPayload(job.getId(), job.getOwnerId(), job.getNodeId(),
                job.getCheckInId(), checkIn.getSummary(), job.getPurpose().name(),
                job.getStatus().name(), job.getRetrySequence(), job.getAttemptCount(), job.getLeaseToken(),
                job.getQuizId(), job.getLastError(), job.getLeaseUntil());
    }

    public static RoadmapQuizJobPayload quickVerification(
            RoadmapQuizGenerationJobEntity job
    ) {
        return new RoadmapQuizJobPayload(job.getId(), job.getOwnerId(), job.getNodeId(),
                null, "诊断掌握后的快速验证", job.getPurpose().name(),
                job.getStatus().name(), job.getRetrySequence(), job.getAttemptCount(),
                job.getLeaseToken(), job.getQuizId(), job.getLastError(), job.getLeaseUntil());
    }
}
