package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.infrastructure.MasteryEntity;

public record MasteryResponse(
        String knowledgePoint,
        double score,
        int attemptCount
) {
    public static MasteryResponse from(MasteryEntity entity) {
        return new MasteryResponse(
                entity.getKnowledgePoint(),
                entity.getScore(),
                entity.getAttemptCount()
        );
    }
}
