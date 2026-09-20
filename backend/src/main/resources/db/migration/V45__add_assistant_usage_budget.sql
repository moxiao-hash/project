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
    -- 用途：AGENT_PLANNING / KNOWLEDGE_QA / QUIZ_GENERATION / CODE_EVALUATION /
    -- RUBRIC_SCORING / MATERIAL_ANALYSIS / PLAN_ADJUSTMENT 等。
    purpose VARCHAR(40) NOT NULL,
    -- 一次模型调用的结果：SUCCEEDED / FAILED。失败也计一次调用与失败率。
    status VARCHAR(20) NOT NULL,
    prompt_tokens INT,
    cached_prompt_tokens INT,
    completion_tokens INT,
    -- reasoning 已包含在 completion 内，仅作为子类明细保存，不参与再次求和。
    reasoning_tokens INT,
    -- 输入 + 输出；reasoning 不重复加入。
    total_tokens INT,
    latency_ms BIGINT,
    estimated_cost DECIMAL(18, 8),
    currency VARCHAR(8),
    price_version VARCHAR(64),
    -- 计价时段：PEAK / OFF_PEAK；历史记录保留当时的时段以便复核金额。
    price_window VARCHAR(10),
    occurred_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE INDEX idx_assistant_model_usage_owner_time
    ON assistant_model_usage (owner_id, occurred_at);

CREATE INDEX idx_assistant_model_usage_turn
    ON assistant_model_usage (conversation_id, turn_id);

CREATE INDEX idx_assistant_model_usage_owner_model
    ON assistant_model_usage (owner_id, model_name, occurred_at);

CREATE TABLE assistant_usage_budget (
    owner_id VARCHAR(36) PRIMARY KEY,
    daily_model_calls INT,
    daily_estimated_cost DECIMAL(18, 8),
    max_output_tokens_per_turn INT,
    currency VARCHAR(8),
    updated_at TIMESTAMP(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0
);
