package com.moxiao.studypilot.agent.automation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FailAutomationJobRequest(
        @NotBlank String workerId,
        @NotBlank String leaseToken,
        @NotBlank @Size(max = 1000) String error
) {
}
