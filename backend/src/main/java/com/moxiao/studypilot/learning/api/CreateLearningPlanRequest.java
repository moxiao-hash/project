package com.moxiao.studypilot.learning.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateLearningPlanRequest(
        @NotBlank String goalId,
        @NotBlank @Size(max = 120) String title,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate
) {
    public CreateLearningPlanRequest {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("计划结束日期不能早于开始日期");
        }
    }
}
