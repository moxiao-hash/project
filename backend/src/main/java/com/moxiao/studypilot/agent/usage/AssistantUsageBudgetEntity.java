package com.moxiao.studypilot.agent.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** 单个用户的模型调用预算；字段为 {@code null} 表示该项不限制。 */
@Entity
@Table(name = "assistant_usage_budget")
public class AssistantUsageBudgetEntity {

    @Id
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(name = "daily_model_calls")
    private Integer dailyModelCalls;

    @Column(name = "daily_estimated_cost", precision = 18, scale = 8)
    private BigDecimal dailyEstimatedCost;

    @Column(name = "max_output_tokens_per_turn")
    private Integer maxOutputTokensPerTurn;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected AssistantUsageBudgetEntity() {
    }

    public AssistantUsageBudgetEntity(
            String ownerId,
            Integer dailyModelCalls,
            BigDecimal dailyEstimatedCost,
            Integer maxOutputTokensPerTurn,
            String currency,
            Instant updatedAt
    ) {
        this.ownerId = ownerId;
        this.dailyModelCalls = dailyModelCalls;
        this.dailyEstimatedCost = dailyEstimatedCost;
        this.maxOutputTokensPerTurn = maxOutputTokensPerTurn;
        this.currency = currency;
        this.updatedAt = updatedAt;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public Integer getDailyModelCalls() {
        return dailyModelCalls;
    }

    public BigDecimal getDailyEstimatedCost() {
        return dailyEstimatedCost;
    }

    public Integer getMaxOutputTokensPerTurn() {
        return maxOutputTokensPerTurn;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getRowVersion() {
        return rowVersion;
    }
}
