package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.domain.LearningGoal;
import com.moxiao.studypilot.learning.domain.LearningGoalStatus;

import java.time.LocalDate;
import java.util.UUID;

public record LearningGoalResponse(
        UUID id,
        String title,
        LocalDate targetDate,
        int weeklyStudyHours,
        LearningGoalStatus status
) {

    public static LearningGoalResponse from(LearningGoal goal) {
        return new LearningGoalResponse(
                goal.id(),
                goal.title(),
                goal.targetDate(),
                goal.weeklyStudyHours(),
                goal.status()
        );
    }
}
