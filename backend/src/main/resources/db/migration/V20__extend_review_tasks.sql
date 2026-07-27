ALTER TABLE learning_tasks
    ADD COLUMN task_kind VARCHAR(30) NOT NULL DEFAULT 'LEARNING',
    ADD COLUMN knowledge_point VARCHAR(180) NULL,
    ADD COLUMN source_attempt_id VARCHAR(36) NULL;

CREATE INDEX idx_learning_tasks_review_dedup
    ON learning_tasks (owner_id, knowledge_point, status);
