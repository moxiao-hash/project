package com.moxiao.studypilot.learning.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "learning_plan_versions")
public class LearningPlanVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "entity_version", nullable = false)
    private int version;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(name = "change_reason", nullable = false, length = 255)
    private String changeReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LearningPlanVersionEntity() {
    }

    public LearningPlanVersionEntity(
            String planId,
            int version,
            String snapshotJson,
            String changeReason,
            Instant createdAt
    ) {
        this.planId = planId;
        this.version = version;
        this.snapshotJson = snapshotJson;
        this.changeReason = changeReason;
        this.createdAt = createdAt;
    }

    public int getVersion() {
        return version;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
