package com.moxiao.studypilot.learning.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface LearningGoalJpaRepository extends JpaRepository<LearningGoalEntity, String> {

    List<LearningGoalEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);

    boolean existsByIdAndOwnerId(String id, String ownerId);

    long countByOwnerId(String ownerId);

    Optional<LearningGoalEntity> findByIdAndOwnerId(String id, String ownerId);
}
