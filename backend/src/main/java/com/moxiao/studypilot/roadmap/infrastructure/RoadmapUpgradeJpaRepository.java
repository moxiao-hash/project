package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoadmapUpgradeJpaRepository extends JpaRepository<RoadmapUpgradeEntity, String> {
    Optional<RoadmapUpgradeEntity> findByOwnerIdAndId(String ownerId, String id);

    Optional<RoadmapUpgradeEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId,
            String idempotencyKey
    );
}
