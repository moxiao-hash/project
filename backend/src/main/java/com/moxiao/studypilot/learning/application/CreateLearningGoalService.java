package com.moxiao.studypilot.learning.application;

import com.moxiao.studypilot.learning.domain.LearningGoal;
import com.moxiao.studypilot.learning.domain.LearningGoalRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class CreateLearningGoalService {

    private final LearningGoalRepository learningGoalRepository;

    public CreateLearningGoalService(LearningGoalRepository learningGoalRepository) {
        this.learningGoalRepository = learningGoalRepository;
    }

    public LearningGoal create(String title, LocalDate targetDate, int weeklyStudyHours) {
        LearningGoal goal = new LearningGoal(title, targetDate, weeklyStudyHours);
        return learningGoalRepository.save(goal);
    }
}
