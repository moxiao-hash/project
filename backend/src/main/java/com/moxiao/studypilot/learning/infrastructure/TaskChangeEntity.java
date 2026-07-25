package com.moxiao.studypilot.learning.infrastructure;

import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "task_changes")
public class TaskChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 20)
    private LearningTaskStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private LearningTaskStatus toStatus;

    @Column(name = "from_scheduled_date", nullable = false)
    private LocalDate fromScheduledDate;

    @Column(name = "to_scheduled_date", nullable = false)
    private LocalDate toScheduledDate;

    @Column(length = 255)
    private String reason;

    @Column(name = "operation_idempotency_key", length = 180)
    private String operationIdempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TaskChangeEntity() {
    }

    public TaskChangeEntity(
            String taskId,
            LearningTaskStatus fromStatus,
            LearningTaskStatus toStatus,
            LocalDate fromScheduledDate,
            LocalDate toScheduledDate,
            String reason,
            Instant createdAt
    ) {
        this(
                taskId,
                fromStatus,
                toStatus,
                fromScheduledDate,
                toScheduledDate,
                reason,
                null,
                createdAt
        );
    }

    public TaskChangeEntity(
            String taskId,
            LearningTaskStatus fromStatus,
            LearningTaskStatus toStatus,
            LocalDate fromScheduledDate,
            LocalDate toScheduledDate,
            String reason,
            String operationIdempotencyKey,
            Instant createdAt
    ) {
        this.taskId = taskId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.fromScheduledDate = fromScheduledDate;
        this.toScheduledDate = toScheduledDate;
        this.reason = reason;
        this.operationIdempotencyKey = operationIdempotencyKey;
        this.createdAt = createdAt;
    }

    public String getTaskId() {
        return taskId;
    }

    public LearningTaskStatus getFromStatus() {
        return fromStatus;
    }

    public LearningTaskStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public String getOperationIdempotencyKey() {
        return operationIdempotencyKey;
    }

    public LocalDate getFromScheduledDate() {
        return fromScheduledDate;
    }

    public LocalDate getToScheduledDate() {
        return toScheduledDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
