package com.moxiao.studypilot.learning.infrastructure;

import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import com.moxiao.studypilot.learning.domain.LearningTaskKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "learning_tasks")
public class LearningTaskEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "estimated_minutes", nullable = false)
    private int estimatedMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LearningTaskStatus status;

    @Column(name = "entity_version", nullable = false)
    private int version;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "actual_minutes")
    private Integer actualMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_kind", nullable = false, length = 30)
    private LearningTaskKind taskKind;

    @Column(name = "knowledge_point", length = 180)
    private String knowledgePoint;

    @Column(name = "source_attempt_id", length = 36)
    private String sourceAttemptId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LearningTaskEntity() {
    }

    public LearningTaskEntity(
            String id,
            String ownerId,
            String planId,
            String title,
            LocalDate scheduledDate,
            int estimatedMinutes,
            Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.planId = planId;
        this.title = title;
        this.scheduledDate = scheduledDate;
        this.estimatedMinutes = estimatedMinutes;
        this.status = LearningTaskStatus.TODO;
        this.taskKind = LearningTaskKind.LEARNING;
        this.version = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public LearningTaskEntity(
            String id,
            String ownerId,
            String planId,
            String title,
            LocalDate scheduledDate,
            int estimatedMinutes,
            LearningTaskKind taskKind,
            String knowledgePoint,
            String sourceAttemptId,
            Instant now
    ) {
        this(id, ownerId, planId, title, scheduledDate, estimatedMinutes, now);
        this.taskKind = taskKind;
        this.knowledgePoint = knowledgePoint;
        this.sourceAttemptId = sourceAttemptId;
    }

    public LearningTaskStatus changeStatus(
            LearningTaskStatus newStatus,
            LocalDate newScheduledDate,
            Integer newActualMinutes,
            Instant now
    ) {
        if (newStatus == LearningTaskStatus.DEFERRED) {
            if (newScheduledDate == null || !newScheduledDate.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("延期任务必须提供今天之后的安排日期");
            }
            scheduledDate = newScheduledDate;
        }
        LearningTaskStatus previous = status;
        status = newStatus;
        completedAt = newStatus == LearningTaskStatus.COMPLETED ? now : null;
        actualMinutes = newStatus == LearningTaskStatus.COMPLETED ? newActualMinutes : null;
        version++;
        updatedAt = now;
        return previous;
    }

    public void reschedule(LocalDate newScheduledDate, Instant now) {
        scheduledDate = newScheduledDate;
        version++;
        updatedAt = now;
    }

    public void updateEstimate(int newEstimatedMinutes, Instant now) {
        estimatedMinutes = newEstimatedMinutes;
        version++;
        updatedAt = now;
    }

    public void replaceForSplit(
            String newTitle,
            int newEstimatedMinutes,
            Instant now
    ) {
        title = newTitle;
        estimatedMinutes = newEstimatedMinutes;
        version++;
        updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getPlanId() {
        return planId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getScheduledDate() {
        return scheduledDate;
    }

    public int getEstimatedMinutes() {
        return estimatedMinutes;
    }

    public LearningTaskStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Integer getActualMinutes() {
        return actualMinutes;
    }

    public LearningTaskKind getTaskKind() { return taskKind; }
    public String getKnowledgePoint() { return knowledgePoint; }
    public String getSourceAttemptId() { return sourceAttemptId; }
}
