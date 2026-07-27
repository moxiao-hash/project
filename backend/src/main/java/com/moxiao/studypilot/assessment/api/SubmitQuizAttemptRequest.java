package com.moxiao.studypilot.assessment.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

public record SubmitQuizAttemptRequest(
        @Size(max = 180) String idempotencyKey,
        @NotEmpty List<@Valid AnswerInput> answers
) {
    public record AnswerInput(
            @NotBlank String questionId,
            Set<@NotBlank String> selectedAnswers,
            @Size(max = 100_000) String codeAnswer
    ) {
    }
}
