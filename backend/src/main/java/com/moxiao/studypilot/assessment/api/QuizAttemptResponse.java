package com.moxiao.studypilot.assessment.api;

import java.util.List;

public record QuizAttemptResponse(
        String id,
        double score,
        String status,
        String warning,
        List<QuestionResult> results
) {
    public record QuestionResult(
            String questionId,
            boolean correct,
            String knowledgePoint,
            String explanation,
            String evaluationMethod,
            Double score,
            Object evaluation
    ) {
    }
}
