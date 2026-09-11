package com.moxiao.studypilot.agent.tool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_tool_actions")
public class AgentToolActionEntity {
    @Id
    private String id;
    @Column(name = "owner_id", nullable = false)
    private String ownerId;
    @Column(name = "execution_id", nullable = false)
    private String executionId;
    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;
    @Column(name = "tool_name", nullable = false, length = 120)
    private String toolName;
    @Column(name = "tool_version", nullable = false)
    private int toolVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 10)
    private AgentToolRiskLevel riskLevel;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AgentToolActionStatus status;
    @Column(nullable = false, length = 500)
    private String summary;
    @Column(name = "arguments_json", nullable = false, columnDefinition = "LONGTEXT")
    private String argumentsJson;
    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;
    @Column(name = "error_message", length = 1000)
    private String errorMessage;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "lease_token", length = 64)
    private String leaseToken;
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentToolActionEntity() {
    }

    public AgentToolActionEntity(
            String id, String ownerId, String executionId, String idempotencyKey,
            String toolName, int toolVersion, AgentToolRiskLevel riskLevel,
            AgentToolActionStatus status, String summary, String argumentsJson,
            Instant expiresAt, Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.executionId = executionId;
        this.idempotencyKey = idempotencyKey;
        this.toolName = toolName;
        this.toolVersion = toolVersion;
        this.riskLevel = riskLevel;
        this.status = status;
        this.summary = summary;
        this.argumentsJson = argumentsJson;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void ready(Instant now) {
        status = AgentToolActionStatus.READY;
        updatedAt = now;
    }

    public void running(Instant now) {
        status = AgentToolActionStatus.RUNNING;
        updatedAt = now;
    }

    /**
     * Task 30：进入 RUNNING 时登记一次带租约的执行尝试。
     *
     * <p>租约是恢复流程的 fencing token：只有持有当前 token 的执行才能写入终态，
     * 过期后恢复流程可以安全接管。</p>
     */
    public void startAttempt(String leaseToken, Instant leaseExpiresAt, Instant now) {
        status = AgentToolActionStatus.RUNNING;
        this.leaseToken = leaseToken;
        this.leaseExpiresAt = leaseExpiresAt;
        this.attemptCount = this.attemptCount + 1;
        this.updatedAt = now;
    }

    public void succeed(String resultJson, Instant now) {
        status = AgentToolActionStatus.SUCCEEDED;
        this.resultJson = resultJson;
        this.errorMessage = null;
        clearLease();
        updatedAt = now;
    }

    public void fail(String error, Instant now) {
        status = AgentToolActionStatus.FAILED;
        errorMessage = error;
        clearLease();
        updatedAt = now;
    }

    public void reject(Instant now) {
        status = AgentToolActionStatus.REJECTED;
        clearLease();
        updatedAt = now;
    }

    private void clearLease() {
        leaseToken = null;
        leaseExpiresAt = null;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getExecutionId() { return executionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getToolName() { return toolName; }
    public int getToolVersion() { return toolVersion; }
    public AgentToolRiskLevel getRiskLevel() { return riskLevel; }
    public AgentToolActionStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public String getArgumentsJson() { return argumentsJson; }
    public String getResultJson() { return resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getLeaseToken() { return leaseToken; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public int getAttemptCount() { return attemptCount; }
}
