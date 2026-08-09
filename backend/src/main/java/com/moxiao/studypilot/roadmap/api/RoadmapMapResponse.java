package com.moxiao.studypilot.roadmap.api;

import java.util.List;

public record RoadmapMapResponse(
        String enrollmentId,
        String roadmapCode,
        int templateVersion,
        String title,
        String description,
        int completedRequiredNodes,
        int totalRequiredNodes,
        List<RoadmapStageResponse> stages
) {
}
