package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapDiagnosticEntity;

import java.time.Instant;
import java.util.List;

public record RoadmapDiagnosticJobResponse(
        String id,
        String ownerId,
        String userRoadmapId,
        String status,
        int questionTarget,
        boolean insufficientQuestionFallback,
        List<RoadmapDiagnosticResponse.NodeSnapshot> nodeSnapshot,
        int attemptCount,
        String leaseToken,
        Instant leaseUntil,
        String quizId,
        String lastError
) {
    public static RoadmapDiagnosticJobResponse from(
            RoadmapDiagnosticEntity entity, RoadmapDiagnosticResponse response
    ) {
        return new RoadmapDiagnosticJobResponse(
                entity.getId(), entity.getOwnerId(), entity.getUserRoadmapId(),
                entity.getStatus().name(), entity.getQuestionTarget(),
                response.insufficientQuestionFallback(), response.nodeSnapshot(),
                entity.getAttemptCount(), entity.getLeaseToken(), entity.getLeaseUntil(),
                entity.getQuizId(), entity.getLastError());
    }
}
