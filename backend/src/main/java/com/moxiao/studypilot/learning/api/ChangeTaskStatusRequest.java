package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ChangeTaskStatusRequest(
        @NotNull LearningTaskStatus status,
        LocalDate scheduledDate,
        @Size(max = 255) String reason,
        @Min(1) @Max(720) Integer actualMinutes
) {
    public ChangeTaskStatusRequest {
        if (actualMinutes != null && status != LearningTaskStatus.COMPLETED) {
            throw new IllegalArgumentException("只有完成任务时可以记录实际学习时长");
        }
    }
}
