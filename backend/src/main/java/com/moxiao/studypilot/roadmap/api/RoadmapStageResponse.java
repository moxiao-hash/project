package com.moxiao.studypilot.roadmap.api;

import java.util.List;

public record RoadmapStageResponse(
        String id,
        String code,
        int order,
        String title,
        String description,
        String graduationProjectTitle,
        int completedRequiredNodes,
        int totalRequiredNodes,
        List<RoadmapModuleResponse.Summary> modules,
        List<RoadmapNodeResponse> nodes
) {
}
