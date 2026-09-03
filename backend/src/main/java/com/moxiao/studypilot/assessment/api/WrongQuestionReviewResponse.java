package com.moxiao.studypilot.assessment.api;

public record WrongQuestionReviewResponse(
        String id,
        String quizId,
        String status,
        int questionCount,
        long remainingCount
) {
}
