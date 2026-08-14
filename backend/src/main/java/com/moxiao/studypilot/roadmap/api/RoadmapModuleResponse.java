package com.moxiao.studypilot.roadmap.api;

import java.util.List;

public record RoadmapModuleResponse(
        String id,
        String stageId,
        String code,
        int order,
        String title,
        String description,
        int completedRequiredNodes,
        int totalRequiredNodes,
        String displayStatus,
        RoadmapNodeResponse milestoneNode,
        List<RoadmapNodeResponse> nodes
) {
    public record Summary(
            String id,
            String code,
            int order,
            String title,
            String description,
            int completedRequiredNodes,
            int totalRequiredNodes,
            String milestoneNodeId,
            String milestoneNodeCode,
            String displayStatus
    ) { }
}
