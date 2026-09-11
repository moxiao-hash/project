ALTER TABLE agent_tool_actions
    ADD COLUMN lease_token VARCHAR(64) NULL,
    ADD COLUMN lease_expires_at TIMESTAMP(6) NULL,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;

CREATE INDEX idx_agent_tool_actions_recovery
    ON agent_tool_actions (status, lease_expires_at);
