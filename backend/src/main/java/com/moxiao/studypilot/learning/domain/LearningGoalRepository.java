package com.moxiao.studypilot.learning.domain;

import java.util.List;
import java.util.Optional;

public interface LearningGoalRepository {

    LearningGoal save(String ownerId, LearningGoal goal);

    List<LearningGoal> findAllByOwnerId(String ownerId);

    boolean existsByIdAndOwnerId(String id, String ownerId);

    long countByOwnerId(String ownerId);

    Optional<LearningGoal> findByIdAndOwnerId(String id, String ownerId);
}
