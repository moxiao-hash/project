package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface RoadmapStageGraduationJpaRepository
        extends JpaRepository<RoadmapStageGraduationEntity, String> {
    Optional<RoadmapStageGraduationEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId, String idempotencyKey);
    Optional<RoadmapStageGraduationEntity> findByOwnerIdAndUserRoadmapIdAndRoadmapStageId(
            String ownerId, String userRoadmapId, String roadmapStageId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from RoadmapStageGraduationEntity g where g.id = :id")
    Optional<RoadmapStageGraduationEntity> findByIdForUpdate(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select g from RoadmapStageGraduationEntity g
            where g.attemptCount < 3 and g.quizId is null and
              (g.status = 'READY' or (g.status = 'LEASED' and g.leaseUntil < :now))
            order by g.createdAt
            """)
    List<RoadmapStageGraduationEntity> findClaimable(Instant now, Pageable pageable);
}
