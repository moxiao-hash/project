package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface RoadmapArtifactJpaRepository extends JpaRepository<RoadmapArtifactEntity, String> {
    List<RoadmapArtifactEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);
    Optional<RoadmapArtifactEntity> findByIdAndOwnerId(String id, String ownerId);
    Optional<RoadmapArtifactEntity> findByOwnerIdAndIdempotencyKey(String ownerId, String idempotencyKey);
    long countByUserRoadmapNodeId(String userRoadmapNodeId);
}
