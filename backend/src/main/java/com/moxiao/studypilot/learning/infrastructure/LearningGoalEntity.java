package com.moxiao.studypilot.learning.infrastructure;

import com.moxiao.studypilot.learning.domain.LearningGoalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "learning_goals")
public class LearningGoalEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "weekly_study_hours", nullable = false)
    private int weeklyStudyHours;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LearningGoalStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LearningGoalEntity() {
    }

    public LearningGoalEntity(
            String id,
            String ownerId,
            String title,
            LocalDate targetDate,
            int weeklyStudyHours,
            LearningGoalStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.title = title;
        this.targetDate = targetDate;
        this.weeklyStudyHours = weeklyStudyHours;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public int getWeeklyStudyHours() {
        return weeklyStudyHours;
    }

    public LearningGoalStatus getStatus() {
        return status;
    }
}
