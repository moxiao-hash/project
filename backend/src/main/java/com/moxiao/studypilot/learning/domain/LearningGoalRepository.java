package com.moxiao.studypilot.learning.domain;

import java.util.List;

public interface LearningGoalRepository {

    LearningGoal save(String ownerId, LearningGoal goal);

    List<LearningGoal> findAllByOwnerId(String ownerId);

    boolean existsByIdAndOwnerId(String id, String ownerId);

    long countByOwnerId(String ownerId);
}
