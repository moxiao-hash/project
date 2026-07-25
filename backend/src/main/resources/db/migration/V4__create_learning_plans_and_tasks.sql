CREATE TABLE learning_plans (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    goal_id VARCHAR(36) NOT NULL,
    title VARCHAR(120) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    entity_version INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_learning_plans_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_learning_plans_goal
        FOREIGN KEY (goal_id) REFERENCES learning_goals (id)
);

CREATE TABLE learning_plan_versions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    plan_id VARCHAR(36) NOT NULL,
    entity_version INT NOT NULL,
    snapshot_json TEXT NOT NULL,
    change_reason VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_plan_versions_plan
        FOREIGN KEY (plan_id) REFERENCES learning_plans (id),
    CONSTRAINT uk_plan_version UNIQUE (plan_id, entity_version)
);

CREATE TABLE learning_tasks (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    plan_id VARCHAR(36) NOT NULL,
    title VARCHAR(160) NOT NULL,
    scheduled_date DATE NOT NULL,
    estimated_minutes INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    entity_version INT NOT NULL,
    completed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_learning_tasks_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_learning_tasks_plan
        FOREIGN KEY (plan_id) REFERENCES learning_plans (id)
);

CREATE TABLE task_changes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id VARCHAR(36) NOT NULL,
    from_status VARCHAR(20) NOT NULL,
    to_status VARCHAR(20) NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_task_changes_task
        FOREIGN KEY (task_id) REFERENCES learning_tasks (id)
);

CREATE INDEX idx_learning_plans_owner ON learning_plans (owner_id);
CREATE INDEX idx_learning_tasks_owner_date ON learning_tasks (owner_id, scheduled_date);
CREATE INDEX idx_task_changes_task ON task_changes (task_id, created_at);
