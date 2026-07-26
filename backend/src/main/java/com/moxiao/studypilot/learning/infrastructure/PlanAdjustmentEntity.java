package com.moxiao.studypilot.learning.infrastructure;

import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.domain.TriggerType;
import com.moxiao.studypilot.learning.domain.PlanAdjustmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "plan_adjustments")
public class PlanAdjustmentEntity {

    @Id
    private String id;
    @Column(name = "owner_id", nullable = false)
    private String ownerId;
    @Column(name = "plan_id", nullable = false)
    private String planId;
    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;
    @Column(name = "analysis_date", nullable = false)
    private LocalDate analysisDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 30)
    private TriggerType triggerType;
    @Column(name = "signals_json", nullable = false, columnDefinition = "TEXT")
    private String signalsJson;
    @Column(nullable = false, length = 500)
    private String summary;
    @Column(name = "operations_json", nullable = false, columnDefinition = "TEXT")
    private String operationsJson;
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 10)
    private RiskLevel riskLevel;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PlanAdjustmentStatus status;
    @Column(name = "execution_id")
    private String executionId;
    @Column(name = "before_plan_version", nullable = false)
    private int beforePlanVersion;
    @Column(name = "after_plan_version")
    private Integer afterPlanVersion;
    @Column(name = "before_snapshot_json", columnDefinition = "TEXT")
    private String beforeSnapshotJson;
    @Column(name = "after_snapshot_json", columnDefinition = "TEXT")
    private String afterSnapshotJson;
    @Column(name = "error_message", length = 1000)
    private String errorMessage;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlanAdjustmentEntity() {
    }

    public PlanAdjustmentEntity(
            String id,
            CreatePlanAdjustmentRequestData data,
            RiskLevel riskLevel,
            PlanAdjustmentStatus status,
            int beforePlanVersion,
            Instant now
    ) {
        this.id = id;
        this.ownerId = data.ownerId();
        this.planId = data.planId();
        this.idempotencyKey = data.idempotencyKey();
        this.analysisDate = data.analysisDate();
        this.triggerType = data.triggerType();
        this.signalsJson = data.signalsJson();
        this.summary = data.summary();
        this.operationsJson = data.operationsJson();
        this.riskLevel = riskLevel;
        this.status = status;
        this.beforePlanVersion = beforePlanVersion;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getPlanId() { return planId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public LocalDate getAnalysisDate() { return analysisDate; }
    public TriggerType getTriggerType() { return triggerType; }
    public String getSignalsJson() { return signalsJson; }
    public String getSummary() { return summary; }
    public String getOperationsJson() { return operationsJson; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public PlanAdjustmentStatus getStatus() { return status; }
    public String getExecutionId() { return executionId; }
    public int getBeforePlanVersion() { return beforePlanVersion; }
    public Integer getAfterPlanVersion() { return afterPlanVersion; }
    public String getBeforeSnapshotJson() { return beforeSnapshotJson; }
    public String getAfterSnapshotJson() { return afterSnapshotJson; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public record CreatePlanAdjustmentRequestData(
            String ownerId,
            String planId,
            String idempotencyKey,
            LocalDate analysisDate,
            TriggerType triggerType,
            String signalsJson,
            String summary,
            String operationsJson
    ) {
    }
}
