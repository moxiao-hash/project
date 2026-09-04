package com.moxiao.studypilot.agent.automation;

import com.moxiao.studypilot.shared.error.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "assistant_automation_jobs")
public class AssistantAutomationJobEntity {

    @Id
    private String id;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "execution_id")
    private String executionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AutomationRuleType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AutomationJobStatus status;

    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Column(name = "lease_token", length = 80)
    private String leaseToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "result_summary", length = 1000)
    private String resultSummary;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AssistantAutomationJobEntity() {
    }

    public AssistantAutomationJobEntity(
            String id,
            String ruleId,
            String ownerId,
            AutomationRuleType type,
            Instant scheduledFor,
            Instant now
    ) {
        this.id = id;
        this.ruleId = ruleId;
        this.ownerId = ownerId;
        this.type = type;
        this.status = AutomationJobStatus.PENDING;
        this.scheduledFor = scheduledFor;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void claim(
            String workerId,
            String leaseToken,
            String executionId,
            Instant leaseUntil,
            Instant now
    ) {
        this.status = AutomationJobStatus.PROCESSING;
        this.workerId = workerId;
        this.leaseToken = leaseToken;
        this.executionId = executionId;
        this.leaseUntil = leaseUntil;
        this.attempts += 1;
        this.updatedAt = now;
    }

    public void heartbeat(String workerId, String leaseToken, Instant leaseUntil, Instant now) {
        requireLease(workerId, leaseToken, now);
        this.leaseUntil = leaseUntil;
        this.updatedAt = now;
    }

    public void complete(String workerId, String leaseToken, String summary, Instant now) {
        requireLease(workerId, leaseToken, now);
        status = AutomationJobStatus.COMPLETED;
        resultSummary = summary;
        clearLease();
        updatedAt = now;
    }

    public void fail(String workerId, String leaseToken, String error, Instant now) {
        requireLease(workerId, leaseToken, now);
        errorMessage = error;
        status = attempts >= 3 ? AutomationJobStatus.FAILED : AutomationJobStatus.PENDING;
        scheduledFor = attempts >= 3 ? scheduledFor : now;
        clearLease();
        updatedAt = now;
    }

    public void reschedule(Instant scheduledFor, Instant now) {
        if (status != AutomationJobStatus.PENDING) {
            throw new ConflictException("只有待处理的主动自动化任务可以重新排期");
        }
        this.scheduledFor = scheduledFor;
        this.updatedAt = now;
    }

    private void requireLease(String workerId, String leaseToken, Instant now) {
        if (status != AutomationJobStatus.PROCESSING
                || !workerId.equals(this.workerId)
                || !leaseToken.equals(this.leaseToken)
                || leaseUntil == null
                || !leaseUntil.isAfter(now)) {
            throw new ConflictException("主动自动化任务租约无效");
        }
    }

    private void clearLease() {
        workerId = null;
        leaseToken = null;
        leaseUntil = null;
    }

    public String getId() { return id; }
    public String getRuleId() { return ruleId; }
    public String getOwnerId() { return ownerId; }
    public String getExecutionId() { return executionId; }
    public AutomationRuleType getType() { return type; }
    public AutomationJobStatus getStatus() { return status; }
    public Instant getScheduledFor() { return scheduledFor; }
    public String getWorkerId() { return workerId; }
    public String getLeaseToken() { return leaseToken; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public int getAttempts() { return attempts; }
    public String getResultSummary() { return resultSummary; }
    public String getErrorMessage() { return errorMessage; }
}
