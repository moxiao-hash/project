package com.moxiao.studypilot.roadmap.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoadmapNodeCheckInRequest(
        @NotBlank @Size(max = 180) String idempotencyKey,
        @NotBlank String summary
) { }
