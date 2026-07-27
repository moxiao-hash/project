package com.moxiao.studypilot.learning.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import com.moxiao.studypilot.learning.domain.LearningTaskStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface LearningTaskJpaRepository extends JpaRepository<LearningTaskEntity, String> {

    Optional<LearningTaskEntity> findByIdAndOwnerId(String id, String ownerId);

    List<LearningTaskEntity> findAllByOwnerIdAndScheduledDateOrderByCreatedAt(
            String ownerId,
            LocalDate scheduledDate
    );

    List<LearningTaskEntity> findAllByOwnerIdOrderByScheduledDateAscCreatedAtAsc(String ownerId);

    List<LearningTaskEntity> findAllByPlanIdOrderByScheduledDateAscCreatedAtAsc(String planId);

    long countByOwnerIdAndScheduledDate(String ownerId, LocalDate scheduledDate);

    long countByOwnerIdAndScheduledDateAndStatus(
            String ownerId,
            LocalDate scheduledDate,
            LearningTaskStatus status
    );

    boolean existsByOwnerIdAndKnowledgePointAndStatusIn(
            String ownerId,
            String knowledgePoint,
            Collection<LearningTaskStatus> statuses
    );
}
