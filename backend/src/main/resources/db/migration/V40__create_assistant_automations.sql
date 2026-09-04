CREATE TABLE assistant_automation_rules (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    timezone VARCHAR(60) NOT NULL,
    local_time TIME NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_assistant_automation_rule_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE INDEX idx_assistant_automation_rule_owner
    ON assistant_automation_rules (owner_id, status, created_at);

CREATE TABLE assistant_automation_settings (
    owner_id VARCHAR(36) PRIMARY KEY,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_assistant_automation_settings_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE TABLE assistant_automation_jobs (
    id VARCHAR(36) PRIMARY KEY,
    rule_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    execution_id VARCHAR(36) NULL,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    scheduled_for TIMESTAMP(6) NOT NULL,
    worker_id VARCHAR(100) NULL,
    lease_token VARCHAR(80) NULL,
    lease_until TIMESTAMP(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    result_summary VARCHAR(1000) NULL,
    error_message VARCHAR(1000) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_assistant_automation_job_rule
        FOREIGN KEY (rule_id) REFERENCES assistant_automation_rules (id),
    CONSTRAINT fk_assistant_automation_job_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_assistant_automation_job_execution
        FOREIGN KEY (execution_id) REFERENCES agent_executions (id),
    CONSTRAINT uk_assistant_automation_job_schedule UNIQUE (rule_id, scheduled_for)
);

CREATE INDEX idx_assistant_automation_job_claim
    ON assistant_automation_jobs (status, scheduled_for, lease_until);
