package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.assessment.api.CreateQuizRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CompleteRoadmapQuizJobRequest(
        @NotBlank @Size(max = 100) String workerId,
        @NotBlank @Size(max = 36) String leaseToken,
        @Size(max = 36) String quizId,
        @Valid QuizDraft quiz
) {
    public record QuizDraft(
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 100) String modelName,
            @NotEmpty @Valid List<CreateQuizRequest.QuestionInput> questions
    ) { }
}
