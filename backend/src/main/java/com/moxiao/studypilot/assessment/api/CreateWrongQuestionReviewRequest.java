package com.moxiao.studypilot.assessment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWrongQuestionReviewRequest(
        @Size(max = 180) String chapterKey,
        @NotBlank @Size(max = 180) String idempotencyKey
) {
}
