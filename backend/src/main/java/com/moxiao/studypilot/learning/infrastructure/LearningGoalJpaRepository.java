package com.moxiao.studypilot.learning.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface LearningGoalJpaRepository extends JpaRepository<LearningGoalEntity, String> {

    List<LearningGoalEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
