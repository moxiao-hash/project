CREATE TABLE agent_grants (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_agent_grants_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE TABLE agent_grant_scopes (
    grant_id VARCHAR(36) NOT NULL,
    scope VARCHAR(40) NOT NULL,
    PRIMARY KEY (grant_id, scope),
    CONSTRAINT fk_grant_scopes_grant
        FOREIGN KEY (grant_id) REFERENCES agent_grants (id)
);

CREATE TABLE agent_executions (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL,
    execution_type VARCHAR(30) NOT NULL,
    trigger_type VARCHAR(30) NOT NULL,
    risk_level VARCHAR(10) NOT NULL,
    required_scope VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    result_summary VARCHAR(1000),
    error_message VARCHAR(1000),
    model_name VARCHAR(100),
    prompt_tokens INT,
    completion_tokens INT,
    latency_ms BIGINT,
    estimated_cost DECIMAL(12, 6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_agent_executions_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT uk_agent_execution_idempotency UNIQUE (owner_id, idempotency_key)
);

CREATE TABLE audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_id VARCHAR(36) NOT NULL,
    action VARCHAR(60) NOT NULL,
    target_type VARCHAR(60) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    details VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_audit_logs_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE INDEX idx_agent_grants_owner ON agent_grants (owner_id, created_at);
CREATE INDEX idx_agent_executions_owner ON agent_executions (owner_id, created_at);
CREATE INDEX idx_audit_logs_owner ON audit_logs (owner_id, created_at);
