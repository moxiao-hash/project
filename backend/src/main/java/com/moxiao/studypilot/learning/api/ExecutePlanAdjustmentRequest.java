package com.moxiao.studypilot.learning.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 执行计划调整时由 Python 编排层提交的治理凭证与乐观锁版本。
 */
public record ExecutePlanAdjustmentRequest(
        @NotBlank String ownerId,
        @NotBlank String executionId,
        @Min(1) int expectedPlanVersion
) {
}
