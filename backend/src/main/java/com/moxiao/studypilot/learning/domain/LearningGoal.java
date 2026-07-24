package com.moxiao.studypilot.learning.domain;

import java.time.LocalDate;
import java.util.Objects;

public record LearningGoal(
        String title,
        LocalDate targetDate,
        int weeklyStudyHours,
        LearningGoalStatus status
) {

    private static final int MIN_WEEKLY_STUDY_HOURS = 1;
    private static final int MAX_WEEKLY_STUDY_HOURS = 40;

    public LearningGoal(String title, LocalDate targetDate, int weeklyStudyHours) {
        this(title, targetDate, weeklyStudyHours, LearningGoalStatus.DRAFT);
    }

    public LearningGoal {
        title = validateTitle(title);
        validateTargetDate(targetDate);
        validateWeeklyStudyHours(weeklyStudyHours);
        Objects.requireNonNull(status, "学习目标状态不能为空");
    }

    private static String validateTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("学习目标名称不能为空");
        }
        if (title.length() > 100) {
            throw new IllegalArgumentException("学习目标名称不能超过 100 个字符");
        }
        return title.trim();
    }

    private static void validateTargetDate(LocalDate targetDate) {
        if (targetDate == null || !targetDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("截止日期必须晚于今天");
        }
    }

    private static void validateWeeklyStudyHours(int weeklyStudyHours) {
        if (weeklyStudyHours < MIN_WEEKLY_STUDY_HOURS
                || weeklyStudyHours > MAX_WEEKLY_STUDY_HOURS) {
            throw new IllegalArgumentException("每周学习时长必须在 1 到 40 小时之间");
        }
    }
}
