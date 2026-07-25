package com.moxiao.studypilot.learning.application;

import com.moxiao.studypilot.learning.domain.LearningGoal;
import com.moxiao.studypilot.learning.domain.LearningGoalRepository;
import com.moxiao.studypilot.learning.api.UpdateLearningGoalRequest;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class CreateLearningGoalService {

    private final LearningGoalRepository learningGoalRepository;

    public CreateLearningGoalService(LearningGoalRepository learningGoalRepository) {
        this.learningGoalRepository = learningGoalRepository;
    }

    public LearningGoal create(
            String ownerId,
            String title,
            LocalDate targetDate,
            int weeklyStudyHours
    ) {
        LearningGoal goal = new LearningGoal(title, targetDate, weeklyStudyHours);
        return learningGoalRepository.save(ownerId, goal);
    }

    public List<LearningGoal> list(String ownerId) {
        return learningGoalRepository.findAllByOwnerId(ownerId);
    }

    public LearningGoal update(
            String ownerId,
            String goalId,
            UpdateLearningGoalRequest request
    ) {
        LearningGoal existing = learningGoalRepository.findByIdAndOwnerId(goalId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("学习目标不存在"));
        LearningGoal updated = new LearningGoal(
                existing.id(),
                request.title(),
                request.targetDate(),
                request.weeklyStudyHours(),
                existing.status()
        );
        return learningGoalRepository.save(ownerId, updated);
    }
}
