package com.moxiao.studypilot.learning.infrastructure;

import com.moxiao.studypilot.learning.domain.LearningPlanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "learning_plans")
public class LearningPlanEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "goal_id", nullable = false)
    private String goalId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LearningPlanStatus status;

    @Column(name = "entity_version", nullable = false)
    private int version;

    @Column(name = "generation_idempotency_key", length = 180)
    private String generationIdempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LearningPlanEntity() {
    }

    public LearningPlanEntity(
            String id,
            String ownerId,
            String goalId,
            String title,
            LocalDate startDate,
            LocalDate endDate,
            Instant now
    ) {
        this(id, ownerId, goalId, title, startDate, endDate, null, now);
    }

    public LearningPlanEntity(
            String id,
            String ownerId,
            String goalId,
            String title,
            LocalDate startDate,
            LocalDate endDate,
            String generationIdempotencyKey,
            Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.goalId = goalId;
        this.title = title;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = LearningPlanStatus.DRAFT;
        this.version = 1;
        this.generationIdempotencyKey = generationIdempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void confirm(Instant now) {
        if (status != LearningPlanStatus.DRAFT) {
            throw new IllegalStateException("只有草案计划可以确认");
        }
        status = LearningPlanStatus.CONFIRMED;
        version++;
        updatedAt = now;
    }

    /**
     * 一个调整草稿无论包含几个任务操作，计划本身只增加一个版本。
     */
    public void applyAdjustment(LocalDate adjustedEndDate, Instant now) {
        if (status != LearningPlanStatus.CONFIRMED) {
            throw new IllegalStateException("只有已确认计划可以调整");
        }
        if (adjustedEndDate != null && adjustedEndDate.isAfter(endDate)) {
            endDate = adjustedEndDate;
        }
        version++;
        updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getGoalId() {
        return goalId;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LearningPlanStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public String getGenerationIdempotencyKey() {
        return generationIdempotencyKey;
    }
}
