package com.moxiao.studypilot.agent.runner;

import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.tool.AgentToolRiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "runner_executions")
public class RunnerExecutionEntity {
    @Id
    @Column(nullable = false, length = 36)
    private String id;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;
    @Column(name = "workspace_path", nullable = false, length = 1024)
    private String workspacePath;
    @Column(name = "workspace_fingerprint", nullable = false, length = 64)
    private String workspaceFingerprint;
    @Enumerated(EnumType.STRING)
    @Column(name = "template_type", nullable = false, length = 40)
    private RunnerTemplateType templateType;
    @Column(name = "target_pattern", length = 255)
    private String targetPattern;
    @Column(name = "command_tokens_json", nullable = false, length = 2000)
    private String commandTokensJson;
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 10)
    private AgentToolRiskLevel riskLevel;
    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;
    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;
    @Column(name = "governance_execution_id", nullable = false, length = 36)
    private String governanceExecutionId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExecutionStatus status;
    @Column(name = "exit_code")
    private Integer exitCode;
    @Column(name = "stdout_summary", length = 2000)
    private String stdoutSummary;
    @Column(name = "stderr_summary", length = 2000)
    private String stderrSummary;
    private Boolean success;
    @Column(name = "duration_millis")
    private Long durationMillis;
    @Column(name = "executed_at")
    private Instant executedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected RunnerExecutionEntity() { }

    public RunnerExecutionEntity(
            String id, String ownerId, String workspaceId, String workspacePath,
            String workspaceFingerprint, RunnerTemplateType templateType, String targetPattern,
            String commandTokensJson, AgentToolRiskLevel riskLevel, int timeoutSeconds,
            String idempotencyKey, String requestFingerprint, String governanceExecutionId,
            ExecutionStatus status, Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.workspaceId = workspaceId;
        this.workspacePath = workspacePath;
        this.workspaceFingerprint = workspaceFingerprint;
        this.templateType = templateType;
        this.targetPattern = targetPattern;
        this.commandTokensJson = commandTokensJson;
        this.riskLevel = riskLevel;
        this.timeoutSeconds = timeoutSeconds;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.governanceExecutionId = governanceExecutionId;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void running(Instant now) {
        this.status = ExecutionStatus.RUNNING;
        this.updatedAt = now;
    }

    public void complete(RunnerExecutionResult result, Instant now) {
        this.status = result.success() ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
        this.exitCode = result.exitCode();
        this.stdoutSummary = result.stdoutSummary();
        this.stderrSummary = result.stderrSummary();
        this.success = result.success();
        this.durationMillis = result.durationMillis();
        this.executedAt = result.executedAt();
        this.updatedAt = now;
    }

    public void reject(Instant now) {
        this.status = ExecutionStatus.REJECTED;
        this.exitCode = -1;
        this.stdoutSummary = "用户已拒绝执行";
        this.stderrSummary = "";
        this.success = false;
        this.durationMillis = 0L;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getWorkspacePath() { return workspacePath; }
    public String getWorkspaceFingerprint() { return workspaceFingerprint; }
    public RunnerTemplateType getTemplateType() { return templateType; }
    public String getTargetPattern() { return targetPattern; }
    public String getCommandTokensJson() { return commandTokensJson; }
    public AgentToolRiskLevel getRiskLevel() { return riskLevel; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getGovernanceExecutionId() { return governanceExecutionId; }
    public ExecutionStatus getStatus() { return status; }
    public Integer getExitCode() { return exitCode; }
    public String getStdoutSummary() { return stdoutSummary; }
    public String getStderrSummary() { return stderrSummary; }
    public Boolean getSuccess() { return success; }
    public Long getDurationMillis() { return durationMillis; }
    public Instant getExecutedAt() { return executedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
