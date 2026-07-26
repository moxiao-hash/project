package com.moxiao.studypilot.material.infrastructure;

import com.moxiao.studypilot.material.domain.MaterialJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "material_processing_jobs")
public class MaterialProcessingJobEntity {

    @Id
    private String id;

    @Column(name = "material_id", nullable = false, unique = true)
    private String materialId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MaterialJobStatus status;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MaterialProcessingJobEntity() {
    }

    public MaterialProcessingJobEntity(String id, String materialId, Instant now) {
        this.id = id;
        this.materialId = materialId;
        this.status = MaterialJobStatus.PENDING;
        this.attemptCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void claim(String workerId, Instant expiresAt, Instant now) {
        status = MaterialJobStatus.LEASED;
        this.workerId = workerId;
        leaseExpiresAt = expiresAt;
        attemptCount++;
        updatedAt = now;
    }

    public void heartbeat(String workerId, Instant expiresAt, Instant now) {
        requireLeaseOwner(workerId);
        leaseExpiresAt = expiresAt;
        updatedAt = now;
    }

    public void fail(String workerId, String error, Instant now) {
        requireLeaseOwner(workerId);
        lastError = error;
        this.workerId = null;
        leaseExpiresAt = null;
        status = attemptCount >= 3 ? MaterialJobStatus.FAILED : MaterialJobStatus.PENDING;
        updatedAt = now;
    }

    public void complete(String workerId, Instant now) {
        requireLeaseOwner(workerId);
        status = MaterialJobStatus.COMPLETED;
        this.workerId = null;
        leaseExpiresAt = null;
        lastError = null;
        updatedAt = now;
    }

    private void requireLeaseOwner(String workerId) {
        if (status != MaterialJobStatus.LEASED || !workerId.equals(this.workerId)) {
            throw new IllegalArgumentException("处理任务租约不属于当前 Worker");
        }
    }

    public String getId() {
        return id;
    }

    public String getMaterialId() {
        return materialId;
    }

    public MaterialJobStatus getStatus() {
        return status;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }
}
