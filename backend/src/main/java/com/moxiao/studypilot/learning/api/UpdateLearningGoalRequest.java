package com.moxiao.studypilot.learning.api;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateLearningGoalRequest(
        @NotBlank @Size(max = 100) String title,
        @NotNull @Future LocalDate targetDate,
        @Min(1) @Max(40) int weeklyStudyHours
) {
}
