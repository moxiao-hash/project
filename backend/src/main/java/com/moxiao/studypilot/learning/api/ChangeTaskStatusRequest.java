package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ChangeTaskStatusRequest(
        @NotNull LearningTaskStatus status,
        LocalDate scheduledDate,
        @Size(max = 255) String reason
) {
}
