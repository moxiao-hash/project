package com.moxiao.studypilot.roadmap.api;

public record RoadmapNodeQuizResponse(
        String nodeId,
        String status,
        String quizId,
        RoadmapQuizGenerationResponse generation
) { }
