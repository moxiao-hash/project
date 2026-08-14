package com.moxiao.studypilot.roadmap.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenewRoadmapQuizJobLeaseRequest(
        @NotBlank @Size(max = 100) String workerId,
        @NotBlank @Size(max = 36) String leaseToken,
        @Min(10) @Max(600) int leaseSeconds
) { }
