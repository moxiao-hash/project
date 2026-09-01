package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface RoadmapDiagnosticJpaRepository
        extends JpaRepository<RoadmapDiagnosticEntity, String> {
    Optional<RoadmapDiagnosticEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId, String idempotencyKey);

    Optional<RoadmapDiagnosticEntity> findFirstByOwnerIdAndUserRoadmapIdOrderByCreatedAtDesc(
            String ownerId, String userRoadmapId);
    Optional<RoadmapDiagnosticEntity> findByQuizId(String quizId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from RoadmapDiagnosticEntity d where d.id = :id")
    Optional<RoadmapDiagnosticEntity> findByIdForUpdate(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from RoadmapDiagnosticEntity d
            where d.attemptCount < 3 and
              (d.status = com.moxiao.studypilot.roadmap.domain.RoadmapDiagnosticStatus.PENDING
               or (d.status = com.moxiao.studypilot.roadmap.domain.RoadmapDiagnosticStatus.LEASED
                   and d.leaseUntil < :now))
            order by d.createdAt
            """)
    List<RoadmapDiagnosticEntity> findClaimable(Instant now, Pageable pageable);
}
