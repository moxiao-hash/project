package com.moxiao.studypilot.agent.automation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ClaimAutomationJobRequest(
        @NotBlank String workerId,
        @Min(10) @Max(600) int leaseSeconds
) {
}
