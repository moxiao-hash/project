ALTER TABLE learning_plans
    ADD COLUMN generation_idempotency_key VARCHAR(180);

CREATE UNIQUE INDEX uk_learning_plans_owner_generation_key
    ON learning_plans (owner_id, generation_idempotency_key);

