package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.domain.QuestionType;
import com.moxiao.studypilot.assessment.domain.CodingKind;
import com.moxiao.studypilot.assessment.domain.Difficulty;
import com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose;
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
        String taskId,
        String lessonId,
        String roadmapNodeId,
        RoadmapQuizPurpose purpose,
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 100) String modelName,
        @NotEmpty List<@Valid QuestionInput> questions
) {
    public record QuestionInput(
            @NotNull QuestionType type,
            Difficulty difficulty,
            CodingKind codingKind,
            String language,
            @NotBlank @Size(max = 180) String knowledgePoint,
            @NotBlank String questionText,
            List<@NotBlank @Size(max = 500) String> options,
            @NotNull Set<@NotBlank @Size(max = 500) String> correctAnswers,
            @NotBlank String explanation,
            String starterCode,
            RubricInput rubric,
            String referenceAnswer,
            List<@Valid SourceInput> sources,
            @Size(max = 64) String questionSignature
    ) {
    }

    public record RubricInput(
            int correctness,
            int completeness,
            int edgeCases,
            int clarityEfficiency
    ) {
    }

    public record SourceInput(
            @NotBlank String sourceType,
            String materialId,
            String webResultId,
            @NotBlank String title,
            String locator,
            @NotBlank String snippet
    ) {
    }
}
