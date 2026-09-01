package com.moxiao.studypilot.roadmap.api;

import java.time.Instant;
import java.util.List;

public record RoadmapStageGraduationResponse(
        String id,
        String userRoadmapId,
        String roadmapStageId,
        String status,
        int questionTarget,
        List<RoadmapDiagnosticResponse.NodeSnapshot> nodeSnapshot,
        String quizId,
        Instant createdAt,
        Instant updatedAt
) { }
