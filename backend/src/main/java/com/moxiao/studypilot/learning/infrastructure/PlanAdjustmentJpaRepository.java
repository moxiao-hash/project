package com.moxiao.studypilot.learning.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlanAdjustmentJpaRepository
        extends JpaRepository<PlanAdjustmentEntity, String> {

    Optional<PlanAdjustmentEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId,
            String idempotencyKey
    );
}
