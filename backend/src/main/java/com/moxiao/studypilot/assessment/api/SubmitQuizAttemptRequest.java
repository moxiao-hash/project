package com.moxiao.studypilot.assessment.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Set;

public record SubmitQuizAttemptRequest(
        @NotEmpty List<@Valid AnswerInput> answers
) {
    public record AnswerInput(
            @NotBlank String questionId,
            @NotEmpty Set<@NotBlank String> selectedAnswers
    ) {
    }
}
