package com.moxiao.studypilot.learning.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CreateConfirmedLearningPlanRequest(
        @NotBlank String ownerId,
        @NotBlank String goalId,
        @NotBlank @Size(max = 180) String idempotencyKey,
        @NotBlank @Size(max = 120) String title,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotNull @Size(min = 1, max = 100) List<@Valid TaskRequest> tasks
) {

    public CreateConfirmedLearningPlanRequest {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("计划结束日期不能早于开始日期");
        }
        if (startDate != null && endDate != null && tasks != null) {
            boolean hasOutOfRangeTask = tasks.stream()
                    .filter(task -> task.scheduledDate() != null)
                    .anyMatch(task -> task.scheduledDate().isBefore(startDate)
                            || task.scheduledDate().isAfter(endDate));
            if (hasOutOfRangeTask) {
                throw new IllegalArgumentException("任务日期必须位于计划日期范围内");
            }
        }
    }

    public CreateLearningPlanRequest toPlanRequest() {
        return new CreateLearningPlanRequest(goalId, title, startDate, endDate);
    }

    public record TaskRequest(
            @NotBlank @Size(max = 160) String title,
            @NotNull LocalDate scheduledDate,
            @Min(5) @Max(720) int estimatedMinutes
    ) {
        public CreateLearningTaskRequest toTaskRequest() {
            return new CreateLearningTaskRequest(title, scheduledDate, estimatedMinutes);
        }
    }
}

