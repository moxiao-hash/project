package com.moxiao.studypilot.roadmap.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClaimRoadmapQuizJobRequest(
        @NotBlank @Size(max = 100) String workerId,
        @Min(10) @Max(600) int leaseSeconds
) { }
