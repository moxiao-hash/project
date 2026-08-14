package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoadmapNodeCheckInJpaRepository
        extends JpaRepository<RoadmapNodeCheckInEntity, String> {
    Optional<RoadmapNodeCheckInEntity> findByUserRoadmapNodeId(String userRoadmapNodeId);
    Optional<RoadmapNodeCheckInEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId, String idempotencyKey);
    List<RoadmapNodeCheckInEntity> findAllByOwnerIdAndUserRoadmapNodeIdOrderByCreatedAtDesc(
            String ownerId, String userRoadmapNodeId);
}
