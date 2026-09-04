package com.moxiao.studypilot.roadmap.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoadmapArtifactRequest(
        @NotBlank @Size(max = 36) String workspaceId,
        @NotBlank @Size(max = 100) String roadmapNodeId,
        @NotBlank @Size(max = 1024) String relativePath,
        @NotBlank @Size(max = 2000) String description,
        @NotBlank @Size(max = 4000) String testEvidence,
        @NotBlank @Size(max = 180) String idempotencyKey
) { }
