package com.moxiao.studypilot.assessment.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SelfAssessmentRequest(@NotEmpty List<@Valid Rating> ratings) {
    public record Rating(
            @NotBlank String knowledgePoint,
            @Min(0) @Max(100) double score
    ) {
    }
}
