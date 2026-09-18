-- Task 31：真实模型用量、价格版本与用户预算。
-- 金额一律使用 DECIMAL，禁止浮点；未知价格写 NULL 表示"不可估算"，不写 0。
-- 幂等键是每次模型调用的 usageId（主键）；统一会话一轮可能调用多次模型，
-- 因此 (conversation_id, turn_id) 只作为聚合索引，不作为唯一键。
-- execution_id 可空：只有该轮进入 Java 治理层时才有对应执行记录，故意不加外键，
-- 避免 AI 侧拿到过期执行号时整条用量记录写入失败。
CREATE TABLE assistant_model_usage (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    turn_id VARCHAR(120) NOT NULL,
    execution_id VARCHAR(36),
    provider VARCHAR(40) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    prompt_tokens INT,
    cached_prompt_tokens INT,
    completion_tokens INT,
    reasoning_tokens INT,
    latency_ms BIGINT,
    estimated_cost DECIMAL(18, 8),
    currency VARCHAR(8),
    price_version VARCHAR(40),
    occurred_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE INDEX idx_assistant_model_usage_owner_time
    ON assistant_model_usage (owner_id, occurred_at);

CREATE INDEX idx_assistant_model_usage_turn
    ON assistant_model_usage (conversation_id, turn_id);

CREATE TABLE assistant_usage_budget (
    owner_id VARCHAR(36) PRIMARY KEY,
    daily_model_calls INT,
    daily_estimated_cost DECIMAL(18, 8),
    max_output_tokens_per_turn INT,
    currency VARCHAR(8),
    updated_at TIMESTAMP(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0
);
