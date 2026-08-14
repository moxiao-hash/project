package com.moxiao.studypilot.roadmap.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FailRoadmapQuizJobRequest(
        @NotBlank @Size(max = 100) String workerId,
        @NotBlank @Size(max = 36) String leaseToken,
        @NotBlank @Size(max = 1000) String error
) { }
