package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "roadmap_stage_graduations")
public class RoadmapStageGraduationEntity {
    @Id private String id;
    @Column(name = "owner_id", nullable = false) private String ownerId;
    @Column(name = "user_roadmap_id", nullable = false) private String userRoadmapId;
    @Column(name = "roadmap_template_id", nullable = false) private String roadmapTemplateId;
    @Column(name = "roadmap_stage_id", nullable = false) private String roadmapStageId;
    @Column(name = "idempotency_key", nullable = false) private String idempotencyKey;
    @Column(nullable = false) private String status;
    @Column(name = "question_target", nullable = false) private int questionTarget;
    @Column(name = "node_snapshot_json", nullable = false, columnDefinition = "LONGTEXT")
    private String nodeSnapshotJson;
    @Column(name = "quiz_id") private String quizId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "worker_id", length = 100) private String workerId;
    @Column(name = "lease_token", length = 36) private String leaseToken;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "last_error", length = 1000) private String lastError;

    protected RoadmapStageGraduationEntity() { }

    public RoadmapStageGraduationEntity(
            String id, String ownerId, String userRoadmapId, String roadmapTemplateId,
            String roadmapStageId, String idempotencyKey, String nodeSnapshotJson, Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.userRoadmapId = userRoadmapId;
        this.roadmapTemplateId = roadmapTemplateId;
        this.roadmapStageId = roadmapStageId;
        this.idempotencyKey = idempotencyKey;
        this.status = "READY";
        this.questionTarget = 10;
        this.nodeSnapshotJson = nodeSnapshotJson;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getUserRoadmapId() { return userRoadmapId; }
    public String getRoadmapTemplateId() { return roadmapTemplateId; }
    public String getRoadmapStageId() { return roadmapStageId; }
    public String getStatus() { return status; }
    public int getQuestionTarget() { return questionTarget; }
    public String getNodeSnapshotJson() { return nodeSnapshotJson; }
    public String getQuizId() { return quizId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getLeaseToken() { return leaseToken; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastError() { return lastError; }

    public void claim(String workerId, int leaseSeconds, Instant now) {
        boolean expired = "LEASED".equals(status) && leaseUntil != null && leaseUntil.isBefore(now);
        if (!"READY".equals(status) && !expired) {
            throw new IllegalStateException("阶段毕业任务不可领取");
        }
        status = "LEASED";
        this.workerId = workerId;
        this.leaseToken = UUID.randomUUID().toString();
        this.leaseUntil = now.plusSeconds(leaseSeconds);
        this.attemptCount++;
        this.updatedAt = now;
    }

    public void requireLease(String workerId, String leaseToken, Instant now) {
        if (!"LEASED".equals(status) || !workerId.equals(this.workerId)
                || !leaseToken.equals(this.leaseToken) || leaseUntil == null
                || !leaseUntil.isAfter(now)) {
            throw new IllegalStateException("阶段毕业任务租约无效");
        }
    }

    public void fail(String workerId, String leaseToken, String error, Instant now) {
        requireLease(workerId, leaseToken, now);
        status = attemptCount >= 3 ? "FAILED" : "READY";
        lastError = error;
        this.workerId = null;
        this.leaseToken = null;
        this.leaseUntil = null;
        updatedAt = now;
    }

    public void bindQuiz(String quizId, Instant now) {
        if (!"LEASED".equals(status)) {
            throw new IllegalStateException("阶段毕业测验必须由有效生成任务绑定");
        }
        if (this.quizId != null && !this.quizId.equals(quizId)) {
            throw new IllegalStateException("阶段毕业测验已经生成");
        }
        this.quizId = quizId;
        this.status = "READY";
        this.leaseUntil = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void complete(double score, String quizId, Instant now) {
        if (!quizId.equals(this.quizId)) {
            throw new IllegalArgumentException("阶段毕业测验绑定不一致");
        }
        if (score >= 70) {
            status = "COMPLETED";
            updatedAt = now;
        }
    }
}
