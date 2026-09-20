package com.moxiao.studypilot.agent.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 每次模型调用的用量明细。
 *
 * <p>主键是 AI 侧生成的 {@code usageId}，同一模型调用重复回调不会重复计费；
 * {@code executionId} 可空，只有进入 Java 治理层的轮次才有值。</p>
 */
@Entity
@Table(name = "assistant_model_usage")
public class AssistantModelUsageEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "turn_id", nullable = false, length = 120)
    private String turnId;

    @Column(name = "execution_id", length = 36)
    private String executionId;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "purpose", nullable = false, length = 40)
    private String purpose;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "cached_prompt_tokens")
    private Integer cachedPromptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "reasoning_tokens")
    private Integer reasoningTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "estimated_cost", precision = 18, scale = 8)
    private BigDecimal estimatedCost;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "price_version", length = 64)
    private String priceVersion;

    @Column(name = "price_window", length = 10)
    private String priceWindow;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AssistantModelUsageEntity() {
    }

    public AssistantModelUsageEntity(
            String id,
            String ownerId,
            String conversationId,
            String turnId,
            String executionId,
            String provider,
            String modelName,
            String purpose,
            String status,
            Integer promptTokens,
            Integer cachedPromptTokens,
            Integer completionTokens,
            Integer reasoningTokens,
            Integer totalTokens,
            Long latencyMs,
            BigDecimal estimatedCost,
            String currency,
            String priceVersion,
            String priceWindow,
            Instant occurredAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.conversationId = conversationId;
        this.turnId = turnId;
        this.executionId = executionId;
        this.provider = provider;
        this.modelName = modelName;
        this.purpose = purpose;
        this.status = status;
        this.promptTokens = promptTokens;
        this.cachedPromptTokens = cachedPromptTokens;
        this.completionTokens = completionTokens;
        this.reasoningTokens = reasoningTokens;
        this.totalTokens = totalTokens;
        this.latencyMs = latencyMs;
        this.estimatedCost = estimatedCost;
        this.currency = currency;
        this.priceVersion = priceVersion;
        this.priceWindow = priceWindow;
        this.occurredAt = occurredAt;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getProvider() {
        return provider;
    }

    public String getModelName() {
        return modelName;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getStatus() {
        return status;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCachedPromptTokens() {
        return cachedPromptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getReasoningTokens() {
        return reasoningTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPriceVersion() {
        return priceVersion;
    }

    public String getPriceWindow() {
        return priceWindow;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
