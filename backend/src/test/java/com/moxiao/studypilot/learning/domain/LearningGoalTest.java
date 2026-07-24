package com.moxiao.studypilot.learning.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LearningGoalTest {

    @Test
    void createsDraftGoalWithValidInput() {
        LocalDate targetDate = LocalDate.now().plusDays(30);

        LearningGoal goal = new LearningGoal(
                "完成 Java + AI 项目",
                targetDate,
                10
        );

        assertEquals("完成 Java + AI 项目", goal.title());
        assertEquals(targetDate, goal.targetDate());
        assertEquals(10, goal.weeklyStudyHours());
        assertEquals(LearningGoalStatus.DRAFT, goal.status());
    }

    @Test
    void rejectsBlankTitle() {
        assertThrows(IllegalArgumentException.class, () -> new LearningGoal(
                "   ",
                LocalDate.now().plusDays(1),
                10
        ));
    }

    @Test
    void rejectsTodayOrPastTargetDate() {
        assertThrows(IllegalArgumentException.class, () -> new LearningGoal(
                "完成 Java + AI 项目",
                LocalDate.now(),
                10
        ));
    }

    @Test
    void rejectsWeeklyStudyHoursOutsideAllowedRange() {
        assertThrows(IllegalArgumentException.class, () -> new LearningGoal(
                "完成 Java + AI 项目",
                LocalDate.now().plusDays(1),
                0
        ));

        assertThrows(IllegalArgumentException.class, () -> new LearningGoal(
                "完成 Java + AI 项目",
                LocalDate.now().plusDays(1),
                41
        ));
    }
}
