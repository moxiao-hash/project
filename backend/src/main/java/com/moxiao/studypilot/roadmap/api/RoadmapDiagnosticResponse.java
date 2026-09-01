package com.moxiao.studypilot.roadmap.api;

import java.time.Instant;
import java.util.List;

public record RoadmapDiagnosticResponse(
        String id,
        String userRoadmapId,
        String status,
        int questionTarget,
        boolean insufficientQuestionFallback,
        List<NodeSnapshot> nodeSnapshot,
        List<String> masteredNodeIds,
        String quizId,
        Instant createdAt,
        Instant updatedAt
) {
    public record NodeSnapshot(
            String nodeId,
            String nodeCode,
            String moduleId,
            String title,
            boolean milestone
    ) { }
}
