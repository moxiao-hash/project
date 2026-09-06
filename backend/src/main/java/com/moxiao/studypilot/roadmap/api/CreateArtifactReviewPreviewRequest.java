package com.moxiao.studypilot.roadmap.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateArtifactReviewPreviewRequest(
        @NotBlank @Size(max = 36) String runnerExecutionId,
        @NotBlank @Size(max = 180) String idempotencyKey
) { }
