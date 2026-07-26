package com.moxiao.studypilot.material.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface MaterialProcessingJobJpaRepository
        extends JpaRepository<MaterialProcessingJobEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from MaterialProcessingJobEntity job
            where job.attemptCount < 3
              and (
                job.status = com.moxiao.studypilot.material.domain.MaterialJobStatus.PENDING
                or (
                  job.status = com.moxiao.studypilot.material.domain.MaterialJobStatus.LEASED
                  and job.leaseExpiresAt < :now
                )
              )
            order by job.createdAt
            """)
    List<MaterialProcessingJobEntity> findClaimable(Instant now, Pageable pageable);
}
