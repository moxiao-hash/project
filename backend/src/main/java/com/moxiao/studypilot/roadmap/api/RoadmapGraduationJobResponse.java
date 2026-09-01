package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageGraduationEntity;

import java.time.Instant;
import java.util.List;

public record RoadmapGraduationJobResponse(
        String id, String ownerId, String userRoadmapId, String roadmapStageId,
        String status, int questionTarget,
        List<RoadmapDiagnosticResponse.NodeSnapshot> nodeSnapshot,
        int attemptCount, String leaseToken, Instant leaseUntil,
        String quizId, String lastError
) {
    public static RoadmapGraduationJobResponse from(
            RoadmapStageGraduationEntity entity, RoadmapStageGraduationResponse response
    ) {
        java.util.LinkedHashMap<String, RoadmapDiagnosticResponse.NodeSnapshot> firstByModule =
                new java.util.LinkedHashMap<>();
        response.nodeSnapshot().forEach(node -> firstByModule.putIfAbsent(node.moduleId(), node));
        java.util.LinkedHashMap<String, RoadmapDiagnosticResponse.NodeSnapshot> selected =
                new java.util.LinkedHashMap<>();
        firstByModule.values().forEach(node -> selected.put(node.nodeId(), node));
        response.nodeSnapshot().forEach(node -> {
            if (selected.size() < 10) {
                selected.putIfAbsent(node.nodeId(), node);
            }
        });
        return new RoadmapGraduationJobResponse(
                entity.getId(), entity.getOwnerId(), entity.getUserRoadmapId(),
                entity.getRoadmapStageId(), entity.getStatus(), entity.getQuestionTarget(),
                List.copyOf(selected.values()).stream().limit(10).toList(),
                entity.getAttemptCount(), entity.getLeaseToken(),
                entity.getLeaseUntil(), entity.getQuizId(), entity.getLastError());
    }
}
