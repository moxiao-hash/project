package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.agent.domain.TriggerType;
import com.moxiao.studypilot.learning.domain.AdaptationSignalType;
import com.moxiao.studypilot.learning.domain.AdjustmentOperationType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CreatePlanAdjustmentRequest(
        @NotBlank String ownerId,
        @NotBlank String planId,
        @NotBlank @Size(max = 180) String idempotencyKey,
        @NotNull LocalDate analysisDate,
        @NotNull TriggerType triggerType,
        @NotNull List<AdaptationSignalType> signals,
        @NotBlank @Size(max = 500) String summary,
        String executionId,
        @NotNull @Size(max = 14) List<@Valid Operation> operations
) {
    public record Operation(
            @NotNull AdjustmentOperationType type,
            @NotBlank String taskId,
            @Min(1) int expectedVersion,
            LocalDate scheduledDate,
            @Min(5) @Max(720) Integer estimatedMinutes,
            @Size(max = 160) String firstTitle,
            @Min(5) @Max(720) Integer firstEstimatedMinutes,
            @Size(max = 160) String secondTitle,
            LocalDate secondScheduledDate,
            @Min(5) @Max(720) Integer secondEstimatedMinutes
    ) {
    }
}
