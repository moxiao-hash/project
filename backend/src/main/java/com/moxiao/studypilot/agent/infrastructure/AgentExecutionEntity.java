package com.moxiao.studypilot.agent.infrastructure;

import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.domain.ExecutionType;
import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.domain.TriggerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "agent_executions")
public class AgentExecutionEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_type", nullable = false, length = 30)
    private ExecutionType executionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 30)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 10)
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "required_scope", nullable = false, length = 40)
    private AgentScope requiredScope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExecutionStatus status;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(name = "result_summary", length = 1000)
    private String resultSummary;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "estimated_cost", precision = 12, scale = 6)
    private BigDecimal estimatedCost;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentExecutionEntity() {
    }

    public AgentExecutionEntity(
            String id,
            String ownerId,
            String idempotencyKey,
            ExecutionType executionType,
            TriggerType triggerType,
            RiskLevel riskLevel,
            AgentScope requiredScope,
            ExecutionStatus status,
            String summary,
            Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.idempotencyKey = idempotencyKey;
        this.executionType = executionType;
        this.triggerType = triggerType;
        this.riskLevel = riskLevel;
        this.requiredScope = requiredScope;
        this.status = status;
        this.summary = summary;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void confirm(Instant now) {
        if (status != ExecutionStatus.WAITING_CONFIRMATION
                && status != ExecutionStatus.WAITING_AUTHORIZATION) {
            throw new IllegalArgumentException("当前执行不需要确认");
        }
        status = ExecutionStatus.PENDING;
        updatedAt = now;
    }

    public void update(
            ExecutionStatus status,
            String resultSummary,
            String errorMessage,
            String modelName,
            Integer promptTokens,
            Integer completionTokens,
            Long latencyMs,
            BigDecimal estimatedCost,
            Instant now
    ) {
        this.status = status;
        this.resultSummary = resultSummary;
        this.errorMessage = errorMessage;
        this.modelName = modelName;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.latencyMs = latencyMs;
        this.estimatedCost = estimatedCost;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public ExecutionType getExecutionType() {
        return executionType;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public AgentScope getRequiredScope() {
        return requiredScope;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public String getSummary() {
        return summary;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getModelName() {
        return modelName;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
