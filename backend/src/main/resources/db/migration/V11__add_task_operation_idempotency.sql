ALTER TABLE task_changes
    ADD COLUMN operation_idempotency_key VARCHAR(180);

CREATE UNIQUE INDEX uk_task_changes_operation_key
    ON task_changes (operation_idempotency_key);
