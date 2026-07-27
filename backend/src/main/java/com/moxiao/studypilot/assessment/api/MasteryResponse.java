package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.infrastructure.MasteryEntity;

import java.time.Instant;

public record MasteryResponse(
        String knowledgePoint,
        double score,
        Double quizScore,
        Double taskScore,
        Double selfAssessmentScore,
        int evidenceCount,
        int attemptCount,
        Instant updatedAt
) {
    public static MasteryResponse from(MasteryEntity entity) {
        return new MasteryResponse(
                entity.getKnowledgePoint(),
                entity.getScore(),
                entity.getQuizScore(),
                entity.getTaskScore(),
                entity.getSelfAssessmentScore(),
                entity.getEvidenceCount(),
                entity.getAttemptCount(),
                entity.getUpdatedAt()
        );
    }
}
