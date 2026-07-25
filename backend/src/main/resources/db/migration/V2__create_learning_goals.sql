CREATE TABLE learning_goals (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    title VARCHAR(100) NOT NULL,
    target_date DATE NOT NULL,
    weekly_study_hours INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_learning_goals_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE INDEX idx_learning_goals_owner_created
    ON learning_goals (owner_id, created_at);
