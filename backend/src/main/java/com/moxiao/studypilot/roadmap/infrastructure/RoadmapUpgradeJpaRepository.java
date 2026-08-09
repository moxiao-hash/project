package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface RoadmapUpgradeJpaRepository extends JpaRepository<RoadmapUpgradeEntity, String> {
    long countByOwnerId(String ownerId);

    Optional<RoadmapUpgradeEntity> findByOwnerIdAndId(String ownerId, String id);

    @Query("select upgrade.userRoadmapId from RoadmapUpgradeEntity upgrade where upgrade.ownerId = :ownerId and upgrade.id = :id")
    Optional<String> findUserRoadmapIdByOwnerIdAndId(
            @Param("ownerId") String ownerId,
            @Param("id") String id
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select upgrade from RoadmapUpgradeEntity upgrade where upgrade.ownerId = :ownerId and upgrade.id = :id")
    Optional<RoadmapUpgradeEntity> findByOwnerIdAndIdForUpdate(
            @Param("ownerId") String ownerId,
            @Param("id") String id
    );

    Optional<RoadmapUpgradeEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId,
            String idempotencyKey
    );
}
