package com.moxiao.studypilot.assessment.api;

import java.util.List;

public record WrongQuestionSummaryResponse(
        long activeCount,
        long masteredCount,
        List<ChapterSummary> chapters,
        WrongQuestionReviewResponse currentReview
) {
    public record ChapterSummary(
            String chapterKey,
            String chapterTitle,
            long activeCount,
            long masteredCount
    ) {
    }
}
