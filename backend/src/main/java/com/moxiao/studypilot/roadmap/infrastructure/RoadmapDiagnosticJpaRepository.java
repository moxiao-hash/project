package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoadmapDiagnosticJpaRepository
        extends JpaRepository<RoadmapDiagnosticEntity, String> {
    Optional<RoadmapDiagnosticEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId, String idempotencyKey);

    Optional<RoadmapDiagnosticEntity> findFirstByOwnerIdAndUserRoadmapIdOrderByCreatedAtDesc(
            String ownerId, String userRoadmapId);
    Optional<RoadmapDiagnosticEntity> findByQuizId(String quizId);
}
