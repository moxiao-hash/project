package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.domain.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

public record CreateQuizRequest(
        @NotBlank String ownerId,
        String materialId,
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 100) String modelName,
        @NotEmpty List<@Valid QuestionInput> questions
) {
    public record QuestionInput(
            @NotNull QuestionType type,
            @NotBlank @Size(max = 180) String knowledgePoint,
            @NotBlank String questionText,
            @Size(min = 2) List<@NotBlank @Size(max = 500) String> options,
            @NotEmpty Set<@NotBlank @Size(max = 500) String> correctAnswers,
            @NotBlank String explanation
    ) {
    }
}
