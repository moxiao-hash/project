ALTER TABLE quiz_attempts ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'GRADED';
ALTER TABLE quiz_attempts ADD COLUMN idempotency_key VARCHAR(180);
ALTER TABLE quiz_attempts ADD COLUMN objective_score DOUBLE NOT NULL DEFAULT 0;
ALTER TABLE quiz_attempts ADD COLUMN evaluation_json TEXT;
ALTER TABLE quiz_attempts ADD COLUMN warning VARCHAR(500);
ALTER TABLE quiz_attempts ADD COLUMN updated_at TIMESTAMP(6);

UPDATE quiz_attempts
SET idempotency_key = id, objective_score = score, updated_at = created_at
WHERE idempotency_key IS NULL;

ALTER TABLE quiz_attempts MODIFY idempotency_key VARCHAR(180) NOT NULL;
ALTER TABLE quiz_attempts MODIFY updated_at TIMESTAMP(6) NOT NULL;
ALTER TABLE quiz_attempts
    ADD CONSTRAINT uk_quiz_attempt_idempotency
        UNIQUE (owner_id, quiz_id, idempotency_key);

CREATE TABLE coding_evaluation_jobs (
    id VARCHAR(36) PRIMARY KEY,
    attempt_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    worker_id VARCHAR(120),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL,
    last_error VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_coding_job_attempt UNIQUE (attempt_id),
    CONSTRAINT fk_coding_job_attempt
        FOREIGN KEY (attempt_id) REFERENCES quiz_attempts (id)
);

CREATE INDEX idx_coding_job_claim ON coding_evaluation_jobs (status, lease_until);
