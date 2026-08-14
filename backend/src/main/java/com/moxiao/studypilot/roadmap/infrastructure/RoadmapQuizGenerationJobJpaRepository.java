package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.RoadmapQuizGenerationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RoadmapQuizGenerationJobJpaRepository
        extends JpaRepository<RoadmapQuizGenerationJobEntity, String> {
    List<RoadmapQuizGenerationJobEntity> findAllByCheckInIdOrderByRetrySequenceDesc(String checkInId);
    Optional<RoadmapQuizGenerationJobEntity> findFirstByUserRoadmapNodeIdOrderByCreatedAtDesc(
            String userRoadmapNodeId);
    Optional<RoadmapQuizGenerationJobEntity> findByIdAndStatus(
            String id, RoadmapQuizGenerationStatus status);
    Optional<RoadmapQuizGenerationJobEntity> findByOwnerIdAndUserRoadmapNodeIdAndRetryIdempotencyKey(
            String ownerId, String userRoadmapNodeId, String retryIdempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from RoadmapQuizGenerationJobEntity job where job.id = :id")
    Optional<RoadmapQuizGenerationJobEntity> findByIdForUpdate(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from RoadmapQuizGenerationJobEntity job
            where job.status = com.moxiao.studypilot.roadmap.domain.RoadmapQuizGenerationStatus.LEASED
              and job.attemptCount >= 3
            order by job.createdAt
            """)
    List<RoadmapQuizGenerationJobEntity> findExpiredExhausted();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from RoadmapQuizGenerationJobEntity job
            where job.attemptCount < 3
              and (job.status = com.moxiao.studypilot.roadmap.domain.RoadmapQuizGenerationStatus.PENDING
                or (job.status = com.moxiao.studypilot.roadmap.domain.RoadmapQuizGenerationStatus.LEASED
                  and job.leaseUntil < :now))
            order by job.createdAt, job.retrySequence
            """)
    List<RoadmapQuizGenerationJobEntity> findClaimable(Instant now, Pageable pageable);
}
