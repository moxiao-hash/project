package com.moxiao.studypilot.roadmap.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoadmapEnrollmentRequest(
        @NotBlank @Size(max = 80) String roadmapCode,
        @Min(1) int templateVersion
) {
}
