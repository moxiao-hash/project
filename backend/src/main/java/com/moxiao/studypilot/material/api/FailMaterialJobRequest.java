package com.moxiao.studypilot.material.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FailMaterialJobRequest(
        @NotBlank @Size(max = 100) String workerId,
        @NotBlank @Size(max = 500) String error
) {
}
