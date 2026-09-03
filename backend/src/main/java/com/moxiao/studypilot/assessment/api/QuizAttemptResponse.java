package com.moxiao.studypilot.assessment.api;

import java.util.List;
import java.util.Set;
import com.moxiao.studypilot.assessment.domain.QuestionType;

public record QuizAttemptResponse(
        String id,
        String quizId,
        double score,
        String status,
        String warning,
        List<QuestionResult> results,
        ReviewProgress reviewProgress
) {
    public record ReviewProgress(int clearedCount, long remainingCount) {
    }
    public record QuestionResult(
            String questionId,
            QuestionType type,
            String questionText,
            List<String> options,
            Set<String> selectedAnswers,
            String codeAnswer,
            Set<String> correctAnswers,
            String referenceAnswer,
            boolean correct,
            String knowledgePoint,
            String explanation,
            String evaluationMethod,
            Double score,
            Object evaluation
    ) {
    }
}
