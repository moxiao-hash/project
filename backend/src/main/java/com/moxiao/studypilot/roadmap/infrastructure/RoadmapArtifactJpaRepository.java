package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoadmapArtifactJpaRepository extends JpaRepository<RoadmapArtifactEntity, String> {
    List<RoadmapArtifactEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);
    Optional<RoadmapArtifactEntity> findByIdAndOwnerId(String id, String ownerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select artifact from RoadmapArtifactEntity artifact "
            + "where artifact.id = :id and artifact.ownerId = :ownerId")
    Optional<RoadmapArtifactEntity> findOwnedForUpdate(
            @Param("id") String id, @Param("ownerId") String ownerId);
    Optional<RoadmapArtifactEntity> findByOwnerIdAndIdempotencyKey(String ownerId, String idempotencyKey);
    long countByUserRoadmapNodeId(String userRoadmapNodeId);
}
