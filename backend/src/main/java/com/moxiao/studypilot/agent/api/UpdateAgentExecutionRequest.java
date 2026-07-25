package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateAgentExecutionRequest(
        @NotNull ExecutionStatus status,
        @Size(max = 1000) String resultSummary,
        @Size(max = 1000) String errorMessage,
        @Size(max = 100) String modelName,
        @PositiveOrZero Integer promptTokens,
        @PositiveOrZero Integer completionTokens,
        @PositiveOrZero Long latencyMs,
        @Min(0) BigDecimal estimatedCost
) {
}
