package com.moxiao.studypilot.learning.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record InternalCreateLearningPlanRequest(
        @NotBlank String ownerId,
        @NotBlank String goalId,
        @NotBlank @Size(max = 120) String title,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate
) {
    public CreateLearningPlanRequest toPlanRequest() {
        return new CreateLearningPlanRequest(goalId, title, startDate, endDate);
    }
}
