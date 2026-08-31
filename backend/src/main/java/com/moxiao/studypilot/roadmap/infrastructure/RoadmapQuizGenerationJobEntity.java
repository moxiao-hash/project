package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.RoadmapQuizGenerationStatus;
import com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "roadmap_quiz_generation_jobs")
public class RoadmapQuizGenerationJobEntity {
    @Id
    @Column(nullable = false, length = 36)
    private String id;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(name = "user_roadmap_id", nullable = false, length = 36)
    private String userRoadmapId;
    @Column(name = "user_roadmap_node_id", nullable = false, length = 36)
    private String userRoadmapNodeId;
    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;
    @Column(name = "check_in_id", nullable = false, length = 36)
    private String checkInId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoadmapQuizPurpose purpose;
    @Column(name = "retry_sequence", nullable = false)
    private int retrySequence;
    @Column(name = "retry_idempotency_key", length = 180)
    private String retryIdempotencyKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoadmapQuizGenerationStatus status;
    @Column(name = "worker_id", length = 120)
    private String workerId;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "lease_token", length = 36)
    private String leaseToken;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "last_error", length = 1000)
    private String lastError;
    @Column(name = "quiz_id", length = 36)
    private String quizId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RoadmapQuizGenerationJobEntity() { }

    public RoadmapQuizGenerationJobEntity(
            String id,
            String ownerId,
            String userRoadmapId,
            String userRoadmapNodeId,
            String nodeId,
            String checkInId,
            RoadmapQuizPurpose purpose,
            int retrySequence,
            Instant now
    ) {
        this(id, ownerId, userRoadmapId, userRoadmapNodeId, nodeId, checkInId,
                purpose, retrySequence, null, now);
    }

    public RoadmapQuizGenerationJobEntity(
            String id, String ownerId, String userRoadmapId, String userRoadmapNodeId,
            String nodeId, String checkInId, RoadmapQuizPurpose purpose, int retrySequence,
            String retryIdempotencyKey, Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.userRoadmapId = userRoadmapId;
        this.userRoadmapNodeId = userRoadmapNodeId;
        this.nodeId = nodeId;
        this.checkInId = checkInId;
        this.purpose = purpose;
        this.retrySequence = retrySequence;
        this.retryIdempotencyKey = retryIdempotencyKey;
        this.status = RoadmapQuizGenerationStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void claim(String workerId, int leaseSeconds, Instant now) {
        if (status != RoadmapQuizGenerationStatus.PENDING
                && !(status == RoadmapQuizGenerationStatus.LEASED
                && leaseUntil != null && leaseUntil.isBefore(now))) {
            throw new IllegalStateException("路线测验生成任务不可领取");
        }
        status = RoadmapQuizGenerationStatus.LEASED;
        this.workerId = workerId;
        leaseToken = UUID.randomUUID().toString();
        leaseUntil = now.plusSeconds(leaseSeconds);
        attemptCount++;
        updatedAt = now;
    }

    public void heartbeat(String workerId, String leaseToken, int leaseSeconds, Instant now) {
        requireActiveLease(workerId, leaseToken, now);
        leaseUntil = now.plusSeconds(leaseSeconds);
        updatedAt = now;
    }

    public boolean complete(String workerId, String leaseToken, String quizId, Instant now) {
        if (status == RoadmapQuizGenerationStatus.COMPLETED) {
            if (workerId.equals(this.workerId) && leaseToken.equals(this.leaseToken)
                    && quizId.equals(this.quizId)) {
                return false;
            }
            throw new IllegalArgumentException("路线测验生成任务已由其他结果完成");
        }
        requireActiveLease(workerId, leaseToken, now);
        status = RoadmapQuizGenerationStatus.COMPLETED;
        this.quizId = quizId;
        leaseUntil = null;
        lastError = null;
        updatedAt = now;
        return true;
    }

    public void fail(String workerId, String leaseToken, String error, Instant now) {
        requireActiveLease(workerId, leaseToken, now);
        lastError = error;
        this.workerId = null;
        this.leaseToken = null;
        leaseUntil = null;
        status = attemptCount >= 3
                ? RoadmapQuizGenerationStatus.FAILED
                : RoadmapQuizGenerationStatus.PENDING;
        updatedAt = now;
    }

    public boolean expireExhaustedLease(Instant now) {
        if (status != RoadmapQuizGenerationStatus.LEASED || attemptCount < 3
                || leaseUntil == null || leaseUntil.isAfter(now)) {
            return false;
        }
        status = RoadmapQuizGenerationStatus.FAILED;
        lastError = "生成任务租约连续三次过期";
        workerId = null;
        leaseToken = null;
        leaseUntil = null;
        updatedAt = now;
        return true;
    }

    private void requireActiveLease(String workerId, String leaseToken, Instant now) {
        if (status != RoadmapQuizGenerationStatus.LEASED
                || !workerId.equals(this.workerId)
                || !leaseToken.equals(this.leaseToken)
                || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("路线测验生成任务租约无效");
        }
    }

    public boolean hasActiveLease(String workerId, String leaseToken, Instant now) {
        return status == RoadmapQuizGenerationStatus.LEASED
                && workerId != null && workerId.equals(this.workerId)
                && leaseToken != null && leaseToken.equals(this.leaseToken)
                && leaseUntil != null && leaseUntil.isAfter(now);
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getUserRoadmapId() { return userRoadmapId; }
    public String getUserRoadmapNodeId() { return userRoadmapNodeId; }
    public String getNodeId() { return nodeId; }
    public String getCheckInId() { return checkInId; }
    public RoadmapQuizPurpose getPurpose() { return purpose; }
    public int getRetrySequence() { return retrySequence; }
    public String getRetryIdempotencyKey() { return retryIdempotencyKey; }
    public RoadmapQuizGenerationStatus getStatus() { return status; }
    public String getWorkerId() { return workerId; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public String getLeaseToken() { return leaseToken; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastError() { return lastError; }
    public String getQuizId() { return quizId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
