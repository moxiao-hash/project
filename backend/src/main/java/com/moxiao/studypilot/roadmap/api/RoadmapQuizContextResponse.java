package com.moxiao.studypilot.roadmap.api;

import java.util.List;

public record RoadmapQuizContextResponse(
        String jobId,
        String ownerId,
        String userRoadmapId,
        String userRoadmapNodeId,
        String roadmapTemplateId,
        NodeContext node,
        List<NodeContext> directPrerequisites,
        List<String> recentQuestionSignatures,
        List<String> officialDomains
) {
    public record NodeContext(
            String id,
            String code,
            String title,
            List<String> objectives,
            List<String> highFrequency,
            List<String> commonMistakes,
            List<Blueprint> quizBlueprint
    ) { }

    public record Blueprint(String prompt, boolean timeSensitive) { }
}
