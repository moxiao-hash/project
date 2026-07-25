package com.moxiao.studypilot.learning.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LearningPlanJpaRepository extends JpaRepository<LearningPlanEntity, String> {

    Optional<LearningPlanEntity> findByIdAndOwnerId(String id, String ownerId);

    Optional<LearningPlanEntity> findByOwnerIdAndGenerationIdempotencyKey(
            String ownerId,
            String generationIdempotencyKey
    );

    List<LearningPlanEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
