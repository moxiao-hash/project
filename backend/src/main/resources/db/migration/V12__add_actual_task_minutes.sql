ALTER TABLE learning_tasks
    ADD COLUMN actual_minutes INT NULL;

ALTER TABLE task_changes
    ADD COLUMN actual_minutes INT NULL;
