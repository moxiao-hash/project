package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeCheckInEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapQuizGenerationJobEntity;

import java.time.Instant;

public record RoadmapNodeCheckInResponse(
        String id,
        String nodeId,
        String summary,
        String idempotencyKey,
        Instant createdAt,
        RoadmapQuizGenerationResponse quizGeneration
) {
    public static RoadmapNodeCheckInResponse from(
            RoadmapNodeCheckInEntity checkIn,
            RoadmapQuizGenerationJobEntity job
    ) {
        return new RoadmapNodeCheckInResponse(
                checkIn.getId(), checkIn.getNodeId(), checkIn.getSummary(),
                checkIn.getIdempotencyKey(), checkIn.getCreatedAt(),
                RoadmapQuizGenerationResponse.from(job));
    }
}
