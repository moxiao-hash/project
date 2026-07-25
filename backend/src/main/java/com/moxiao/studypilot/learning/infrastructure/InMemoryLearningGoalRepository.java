package com.moxiao.studypilot.learning.infrastructure;

import com.moxiao.studypilot.learning.domain.LearningGoal;
import com.moxiao.studypilot.learning.domain.LearningGoalRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryLearningGoalRepository implements LearningGoalRepository {

    private final Map<UUID, LearningGoal> goals = new ConcurrentHashMap<>();

    @Override
    public LearningGoal save(LearningGoal goal) {
        goals.put(goal.id(), goal);
        return goal;
    }
}
