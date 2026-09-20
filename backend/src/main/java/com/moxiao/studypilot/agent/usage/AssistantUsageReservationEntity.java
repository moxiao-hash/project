package com.moxiao.studypilot.agent.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一次模型调用的预占许可。
 *
 * <p>主键复用 AI 侧每次调用生成的 {@code usageId}：预占请求重试命中同一行，
 * 后续用量回调也以同一 id 终结，因此重试与重复回调都不会重复扣减配额。</p>
 */
@Entity
@Table(name = "assistant_usage_reservation")
public class AssistantUsageReservationEntity {

    public static final String STATE_RESERVED = "RESERVED";
    public static final String STATE_FINALIZED = "FINALIZED";
    public static final String STATE_RELEASED = "RELEASED";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "turn_id", nullable = false, length = 120)
    private String turnId;

    @Column(name = "purpose", nullable = false, length = 40)
    private String purpose;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "state", nullable = false, length = 20)
    private String state;

    /**
     * 预占时由 AI 侧给出的当前请求输入 token 上界；费用上限生效时不允许为空。
     */
    @Column(name = "input_tokens_upper_bound")
    private Integer inputTokensUpperBound;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @Column(name = "reserved_cost", precision = 18, scale = 8)
    private BigDecimal reservedCost;

    @Column(name = "actual_cost", precision = 18, scale = 8)
    private BigDecimal actualCost;

    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    protected AssistantUsageReservationEntity() {
    }

    public AssistantUsageReservationEntity(
            String id,
            String ownerId,
            String conversationId,
            String turnId,
            String purpose,
            String provider,
            String modelName,
            Integer inputTokensUpperBound,
            Integer maxOutputTokens,
            BigDecimal reservedCost,
            Instant reservedAt,
            Instant expiresAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.conversationId = conversationId;
        this.turnId = turnId;
        this.purpose = purpose;
        this.provider = provider;
        this.modelName = modelName;
        this.state = STATE_RESERVED;
        this.inputTokensUpperBound = inputTokensUpperBound;
        this.maxOutputTokens = maxOutputTokens;
        this.reservedCost = reservedCost;
        this.reservedAt = reservedAt;
        this.expiresAt = expiresAt;
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

    public String getPurpose() {
        return purpose;
    }

    public String getProvider() {
        return provider;
    }

    public String getModelName() {
        return modelName;
    }

    public String getState() {
        return state;
    }

    public Integer getInputTokensUpperBound() {
        return inputTokensUpperBound;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public BigDecimal getReservedCost() {
        return reservedCost;
    }

    public BigDecimal getActualCost() {
        return actualCost;
    }

    public Instant getReservedAt() {
        return reservedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getFinalizedAt() {
        return finalizedAt;
    }
}
