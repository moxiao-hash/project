-- Task 31：真实模型用量、价格版本与用户预算。
-- 金额一律使用 DECIMAL，禁止浮点；未知价格写 NULL 表示"不可估算"，不写 0。
CREATE TABLE assistant_model_usage (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    execution_id VARCHAR(36) NOT NULL,
    turn_id VARCHAR(120) NOT NULL,
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
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_assistant_model_usage_turn UNIQUE (execution_id, turn_id),
    CONSTRAINT fk_assistant_model_usage_execution
        FOREIGN KEY (execution_id) REFERENCES agent_executions (id)
);

CREATE INDEX idx_assistant_model_usage_owner_time
    ON assistant_model_usage (owner_id, occurred_at);

CREATE TABLE assistant_usage_budget (
    owner_id VARCHAR(36) PRIMARY KEY,
    daily_model_calls INT,
    daily_estimated_cost DECIMAL(18, 8),
    max_output_tokens_per_turn INT,
    currency VARCHAR(8),
    updated_at TIMESTAMP(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0
);
