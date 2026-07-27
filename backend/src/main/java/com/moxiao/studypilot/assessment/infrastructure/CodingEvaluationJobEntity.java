package com.moxiao.studypilot.assessment.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "coding_evaluation_jobs")
public class CodingEvaluationJobEntity {
    @Id
    private String id;
    @Column(name = "attempt_id", nullable = false, unique = true)
    private String attemptId;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "worker_id", length = 120)
    private String workerId;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "last_error", length = 1000)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CodingEvaluationJobEntity() {
    }

    public CodingEvaluationJobEntity(String id, String attemptId, Instant now) {
        this.id = id;
        this.attemptId = attemptId;
        this.status = "PENDING";
        this.attemptCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean claimable(Instant now) {
        return status.equals("PENDING")
                || (status.equals("PROCESSING") && leaseUntil != null && leaseUntil.isBefore(now));
    }

    public void claim(String workerId, int leaseSeconds, Instant now) {
        this.status = "PROCESSING";
        this.workerId = workerId;
        this.leaseUntil = now.plusSeconds(leaseSeconds);
        this.attemptCount++;
        this.updatedAt = now;
    }

    public void complete(Instant now) {
        status = "COMPLETED";
        leaseUntil = null;
        updatedAt = now;
    }

    /**
     * 评估可能需要等待大模型返回，Worker 会定期续租，避免任务被其他进程重复领取。
     */
    public void heartbeat(int leaseSeconds, Instant now) {
        leaseUntil = now.plusSeconds(leaseSeconds);
        updatedAt = now;
    }

    public void fail(String error, Instant now) {
        lastError = error;
        leaseUntil = null;
        status = attemptCount >= 3 ? "FAILED" : "PENDING";
        updatedAt = now;
    }

    public String getId() { return id; }
    public String getAttemptId() { return attemptId; }
    public String getWorkerId() { return workerId; }
    public String getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
}
