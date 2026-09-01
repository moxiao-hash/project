CREATE TABLE roadmap_diagnostics (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    user_roadmap_id VARCHAR(36) NOT NULL,
    roadmap_template_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL,
    status VARCHAR(24) NOT NULL,
    question_target INT NOT NULL,
    insufficient_question_fallback BOOLEAN NOT NULL,
    node_snapshot_json LONGTEXT NOT NULL,
    quiz_id VARCHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_roadmap_diagnostic_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_roadmap_diagnostic_enrollment_scope
        FOREIGN KEY (user_roadmap_id, owner_id) REFERENCES user_roadmaps (id, owner_id),
    CONSTRAINT fk_roadmap_diagnostic_template
        FOREIGN KEY (roadmap_template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT fk_roadmap_diagnostic_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes (id),
    CONSTRAINT uk_roadmap_diagnostic_idempotency UNIQUE (owner_id, idempotency_key),
    CONSTRAINT ck_roadmap_diagnostic_status
        CHECK (status IN ('PENDING', 'READY', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_roadmap_diagnostic_target CHECK (question_target BETWEEN 1 AND 10)
);

CREATE INDEX idx_roadmap_diagnostic_owner_created
    ON roadmap_diagnostics (owner_id, created_at);
