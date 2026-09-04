package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.domain.ArtifactEvaluationMode;
import com.moxiao.studypilot.roadmap.domain.ArtifactStatus;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactReviewEntity;

import java.time.Instant;
import java.util.List;

public record RoadmapArtifactResponse(
        String id,
        String workspaceId,
        String relativePath,
        String canonicalPath,
        String description,
        String testEvidence,
        ArtifactEvaluationMode evaluationMode,
        ArtifactStatus status,
        int submissionVersion,
        RoadmapNodeSnapshot roadmapNode,
        List<ReviewEvent> reviewHistory,
        Instant createdAt
) {
    public static RoadmapArtifactResponse from(
            RoadmapArtifactEntity entity,
            List<RoadmapArtifactReviewEntity> reviews
    ) {
        return new RoadmapArtifactResponse(
                entity.getId(), entity.getWorkspaceId(), entity.getRelativePath(),
                entity.getCanonicalPath(), entity.getDescription(), entity.getTestEvidence(),
                entity.getEvaluationMode(), entity.getStatus(), entity.getSubmissionVersion(),
                new RoadmapNodeSnapshot(
                        entity.getRoadmapNodeId(), entity.getRoadmapModuleId(),
                        entity.getRoadmapStageId(), entity.getNodeTitle(),
                        entity.getModuleTitle(), entity.getStageTitle()),
                reviews.stream().map(ReviewEvent::from).toList(), entity.getCreatedAt());
    }

    public record RoadmapNodeSnapshot(
            String id,
            String moduleId,
            String stageId,
            String title,
            String moduleTitle,
            String stageTitle
    ) { }

    public record ReviewEvent(
            String id,
            ArtifactStatus fromStatus,
            ArtifactStatus toStatus,
            String eventType,
            String details,
            Instant createdAt
    ) {
        static ReviewEvent from(RoadmapArtifactReviewEntity entity) {
            return new ReviewEvent(
                    entity.getId(), entity.getFromStatus(), entity.getToStatus(),
                    entity.getEventType(), entity.getDetails(), entity.getCreatedAt());
        }
    }
}
