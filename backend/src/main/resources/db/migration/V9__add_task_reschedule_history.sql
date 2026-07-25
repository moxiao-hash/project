ALTER TABLE task_changes
    ADD COLUMN from_scheduled_date DATE NULL,
    ADD COLUMN to_scheduled_date DATE NULL;

UPDATE task_changes tc
JOIN learning_tasks task ON task.id = tc.task_id
SET tc.from_scheduled_date = task.scheduled_date,
    tc.to_scheduled_date = task.scheduled_date
WHERE tc.from_scheduled_date IS NULL;

ALTER TABLE task_changes
    MODIFY from_scheduled_date DATE NOT NULL,
    MODIFY to_scheduled_date DATE NOT NULL;
