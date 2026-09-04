CREATE TABLE agent_tool_actions (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    execution_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL,
    tool_name VARCHAR(120) NOT NULL,
    tool_version INT NOT NULL,
    risk_level VARCHAR(10) NOT NULL,
    status VARCHAR(30) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    arguments_json LONGTEXT NOT NULL,
    result_json LONGTEXT NULL,
    error_message VARCHAR(1000) NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_agent_tool_actions_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_agent_tool_actions_execution FOREIGN KEY (execution_id) REFERENCES agent_executions (id),
    CONSTRAINT uk_agent_tool_action_idempotency UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX idx_agent_tool_actions_owner_status
    ON agent_tool_actions (owner_id, status, created_at);
