package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoadmapStageGraduationJpaRepository
        extends JpaRepository<RoadmapStageGraduationEntity, String> {
    Optional<RoadmapStageGraduationEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId, String idempotencyKey);
    Optional<RoadmapStageGraduationEntity> findByOwnerIdAndUserRoadmapIdAndRoadmapStageId(
            String ownerId, String userRoadmapId, String roadmapStageId);
}
