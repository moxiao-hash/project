CREATE TABLE plan_adjustments (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    plan_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL,
    analysis_date DATE NOT NULL,
    trigger_type VARCHAR(30) NOT NULL,
    signals_json TEXT NOT NULL,
    summary VARCHAR(500) NOT NULL,
    operations_json TEXT NOT NULL,
    risk_level VARCHAR(10) NOT NULL,
    status VARCHAR(30) NOT NULL,
    execution_id VARCHAR(36) NULL,
    before_plan_version INT NOT NULL,
    after_plan_version INT NULL,
    before_snapshot_json TEXT NULL,
    after_snapshot_json TEXT NULL,
    error_message VARCHAR(1000) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_plan_adjustments_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_plan_adjustments_user
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_plan_adjustments_plan
        FOREIGN KEY (plan_id) REFERENCES learning_plans (id)
);

CREATE INDEX idx_plan_adjustments_owner_created
    ON plan_adjustments (owner_id, created_at);
