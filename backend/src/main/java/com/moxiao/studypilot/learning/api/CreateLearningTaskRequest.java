package com.moxiao.studypilot.learning.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateLearningTaskRequest(
        @NotBlank @Size(max = 160) String title,
        @NotNull LocalDate scheduledDate,
        @Min(5) @Max(720) int estimatedMinutes
) {
}
