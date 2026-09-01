package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.RoadmapDiagnosticStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "roadmap_diagnostics")
public class RoadmapDiagnosticEntity {
    @Id
    @Column(nullable = false, length = 36)
    private String id;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(name = "user_roadmap_id", nullable = false, length = 36)
    private String userRoadmapId;
    @Column(name = "roadmap_template_id", nullable = false, length = 36)
    private String roadmapTemplateId;
    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RoadmapDiagnosticStatus status;
    @Column(name = "question_target", nullable = false)
    private int questionTarget;
    @Column(name = "insufficient_question_fallback", nullable = false)
    private boolean insufficientQuestionFallback;
    @Column(name = "node_snapshot_json", nullable = false, columnDefinition = "LONGTEXT")
    private String nodeSnapshotJson;
    @Column(name = "quiz_id", length = 36)
    private String quizId;
    @Column(name = "mastered_node_ids_json", nullable = false, columnDefinition = "LONGTEXT")
    private String masteredNodeIdsJson = "[]";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "worker_id", length = 100) private String workerId;
    @Column(name = "lease_token", length = 36) private String leaseToken;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "last_error", length = 1000) private String lastError;

    protected RoadmapDiagnosticEntity() { }

    public RoadmapDiagnosticEntity(
            String id, String ownerId, String userRoadmapId, String roadmapTemplateId,
            String idempotencyKey, int questionTarget, boolean insufficientQuestionFallback,
            String nodeSnapshotJson, Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.userRoadmapId = userRoadmapId;
        this.roadmapTemplateId = roadmapTemplateId;
        this.idempotencyKey = idempotencyKey;
        this.status = RoadmapDiagnosticStatus.PENDING;
        this.questionTarget = questionTarget;
        this.insufficientQuestionFallback = insufficientQuestionFallback;
        this.nodeSnapshotJson = nodeSnapshotJson;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getUserRoadmapId() { return userRoadmapId; }
    public String getRoadmapTemplateId() { return roadmapTemplateId; }
    public RoadmapDiagnosticStatus getStatus() { return status; }
    public int getQuestionTarget() { return questionTarget; }
    public boolean isInsufficientQuestionFallback() { return insufficientQuestionFallback; }
    public String getNodeSnapshotJson() { return nodeSnapshotJson; }
    public String getQuizId() { return quizId; }
    public String getMasteredNodeIdsJson() { return masteredNodeIdsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getWorkerId() { return workerId; }
    public String getLeaseToken() { return leaseToken; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastError() { return lastError; }

    public void claim(String workerId, int leaseSeconds, Instant now) {
        boolean expired = status == RoadmapDiagnosticStatus.LEASED
                && leaseUntil != null && leaseUntil.isBefore(now);
        if (status != RoadmapDiagnosticStatus.PENDING && !expired) {
            throw new IllegalStateException("路线诊断任务不可领取");
        }
        status = RoadmapDiagnosticStatus.LEASED;
        this.workerId = workerId;
        this.leaseToken = UUID.randomUUID().toString();
        this.leaseUntil = now.plusSeconds(leaseSeconds);
        this.attemptCount++;
        this.updatedAt = now;
    }

    public void heartbeat(String workerId, String leaseToken, int leaseSeconds, Instant now) {
        requireLease(workerId, leaseToken, now);
        leaseUntil = now.plusSeconds(leaseSeconds);
        updatedAt = now;
    }

    public void fail(String workerId, String leaseToken, String error, Instant now) {
        requireLease(workerId, leaseToken, now);
        lastError = error;
        status = attemptCount >= 3 ? RoadmapDiagnosticStatus.FAILED : RoadmapDiagnosticStatus.PENDING;
        this.workerId = null;
        this.leaseToken = null;
        this.leaseUntil = null;
        this.updatedAt = now;
    }

    public void requireLease(String workerId, String leaseToken, Instant now) {
        if (status != RoadmapDiagnosticStatus.LEASED
                || !workerId.equals(this.workerId) || !leaseToken.equals(this.leaseToken)
                || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalStateException("路线诊断任务租约无效");
        }
    }

    public void bindQuiz(String quizId, Instant now) {
        if (status != RoadmapDiagnosticStatus.LEASED) {
            throw new IllegalStateException("路线诊断当前不能绑定测验");
        }
        this.quizId = quizId;
        this.status = RoadmapDiagnosticStatus.READY;
        this.leaseUntil = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void complete(String quizId, String masteredNodeIdsJson, Instant now) {
        if (status == RoadmapDiagnosticStatus.COMPLETED) {
            return;
        }
        if (status != RoadmapDiagnosticStatus.READY || !quizId.equals(this.quizId)) {
            throw new IllegalStateException("路线诊断测验绑定不一致");
        }
        this.masteredNodeIdsJson = masteredNodeIdsJson;
        this.status = RoadmapDiagnosticStatus.COMPLETED;
        this.updatedAt = now;
    }
}
