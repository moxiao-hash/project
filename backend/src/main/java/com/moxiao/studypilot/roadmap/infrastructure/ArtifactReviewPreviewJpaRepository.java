package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ArtifactReviewPreviewJpaRepository
        extends JpaRepository<ArtifactReviewPreviewEntity, String> {
    Optional<ArtifactReviewPreviewEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select preview from ArtifactReviewPreviewEntity preview "
            + "where preview.id = :id and preview.artifactId = :artifactId "
            + "and preview.ownerId = :ownerId")
    Optional<ArtifactReviewPreviewEntity> findOwnedForUpdate(
            @Param("id") String id,
            @Param("artifactId") String artifactId,
            @Param("ownerId") String ownerId);
}
