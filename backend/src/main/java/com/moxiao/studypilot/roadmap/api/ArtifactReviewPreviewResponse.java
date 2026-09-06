package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.domain.ArtifactReviewPreviewStatus;
import com.moxiao.studypilot.roadmap.infrastructure.ArtifactReviewFileCollector;

import java.time.Instant;
import java.util.List;

public record ArtifactReviewPreviewResponse(
        String id,
        String artifactId,
        String runnerExecutionId,
        String runnerTemplateType,
        ArtifactReviewPreviewStatus status,
        List<ArtifactReviewFileCollector.FileSnapshot> files,
        List<String> excludedPaths,
        long totalBytes,
        Instant expiresAt,
        String modelName,
        String error
) { }
