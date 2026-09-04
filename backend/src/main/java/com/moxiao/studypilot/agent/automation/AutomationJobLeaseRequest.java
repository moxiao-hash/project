package com.moxiao.studypilot.agent.automation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AutomationJobLeaseRequest(
        @NotBlank String workerId,
        @NotBlank String leaseToken,
        @Min(10) @Max(600) int leaseSeconds
) {
}
