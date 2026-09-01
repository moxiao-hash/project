CREATE TABLE roadmap_stage_graduations (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    user_roadmap_id VARCHAR(36) NOT NULL,
    roadmap_template_id VARCHAR(36) NOT NULL,
    roadmap_stage_id VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL,
    status VARCHAR(20) NOT NULL,
    question_target INT NOT NULL,
    node_snapshot_json LONGTEXT NOT NULL,
    quiz_id VARCHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_stage_graduation_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_stage_graduation_enrollment_scope
        FOREIGN KEY (user_roadmap_id, owner_id) REFERENCES user_roadmaps (id, owner_id),
    CONSTRAINT fk_stage_graduation_template
        FOREIGN KEY (roadmap_template_id) REFERENCES roadmap_templates (id),
    CONSTRAINT fk_stage_graduation_stage
        FOREIGN KEY (roadmap_stage_id) REFERENCES roadmap_stages (id),
    CONSTRAINT fk_stage_graduation_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes (id),
    CONSTRAINT uk_stage_graduation_idempotency UNIQUE (owner_id, idempotency_key),
    CONSTRAINT uk_stage_graduation_scope UNIQUE (user_roadmap_id, roadmap_stage_id),
    CONSTRAINT ck_stage_graduation_status CHECK (status IN ('READY', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_stage_graduation_target CHECK (question_target = 10)
);
