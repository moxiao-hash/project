package com.moxiao.studypilot.roadmap.api;

import java.util.List;

public record RoadmapNodeResponse(
        String id,
        String code,
        int order,
        String title,
        List<String> objectives,
        List<String> highFrequency,
        List<String> commonMistakes,
        List<String> searchKeywords,
        int estimatedMinutes,
        int practiceMinutes,
        String difficulty,
        boolean required,
        List<String> prerequisiteCodes,
        String availabilityStatus,
        String learningStatus,
        String checkInStatus,
        String quizStatus,
        String artifactStatus,
        String completionStatus,
        String displayStatus,
        long version
) {
}
