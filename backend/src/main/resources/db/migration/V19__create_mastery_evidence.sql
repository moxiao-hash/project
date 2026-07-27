ALTER TABLE mastery_records
    ADD COLUMN quiz_score DOUBLE NULL,
    ADD COLUMN task_score DOUBLE NULL,
    ADD COLUMN self_assessment_score DOUBLE NULL,
    ADD COLUMN evidence_count INT NOT NULL DEFAULT 0;

UPDATE mastery_records
SET quiz_score = score,
    evidence_count = attempt_count;

CREATE TABLE mastery_evidence (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    knowledge_point VARCHAR(180) NOT NULL,
    evidence_type VARCHAR(30) NOT NULL,
    score DOUBLE NOT NULL,
    evidence_weight DOUBLE NOT NULL,
    source_reference VARCHAR(180) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_mastery_evidence_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE INDEX idx_mastery_evidence_owner_point
    ON mastery_evidence (owner_id, knowledge_point, created_at);
