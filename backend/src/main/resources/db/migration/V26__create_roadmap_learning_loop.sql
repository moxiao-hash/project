CREATE TABLE roadmap_node_check_ins (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    user_roadmap_id VARCHAR(36) NOT NULL,
    user_roadmap_node_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    summary VARCHAR(2000) NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_roadmap_check_in_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_roadmap_check_in_enrollment_scope
        FOREIGN KEY (user_roadmap_id, owner_id)
        REFERENCES user_roadmaps (id, owner_id),
    CONSTRAINT fk_roadmap_check_in_user_node_scope
        FOREIGN KEY (user_roadmap_node_id, owner_id)
        REFERENCES user_roadmap_nodes (id, owner_id),
    CONSTRAINT fk_roadmap_check_in_node FOREIGN KEY (node_id) REFERENCES roadmap_nodes (id),
    CONSTRAINT uk_roadmap_check_in_user_node UNIQUE (user_roadmap_node_id),
    CONSTRAINT uk_roadmap_check_in_job_scope UNIQUE (id, owner_id, user_roadmap_node_id),
    CONSTRAINT uk_roadmap_check_in_idempotency UNIQUE (owner_id, idempotency_key)
);

CREATE TABLE roadmap_quiz_generation_jobs (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    user_roadmap_id VARCHAR(36) NOT NULL,
    user_roadmap_node_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    check_in_id VARCHAR(36) NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    retry_sequence INT NOT NULL,
    retry_idempotency_key VARCHAR(180),
    status VARCHAR(20) NOT NULL,
    worker_id VARCHAR(120),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL,
    last_error VARCHAR(1000),
    quiz_id VARCHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_roadmap_quiz_job_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_roadmap_quiz_job_enrollment_scope
        FOREIGN KEY (user_roadmap_id, owner_id)
        REFERENCES user_roadmaps (id, owner_id),
    CONSTRAINT fk_roadmap_quiz_job_user_node_scope
        FOREIGN KEY (user_roadmap_node_id, owner_id)
        REFERENCES user_roadmap_nodes (id, owner_id),
    CONSTRAINT fk_roadmap_quiz_job_node FOREIGN KEY (node_id) REFERENCES roadmap_nodes (id),
    CONSTRAINT fk_roadmap_quiz_job_check_in_scope
        FOREIGN KEY (check_in_id, owner_id, user_roadmap_node_id)
        REFERENCES roadmap_node_check_ins (id, owner_id, user_roadmap_node_id),
    CONSTRAINT fk_roadmap_quiz_job_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes (id),
    CONSTRAINT uk_roadmap_quiz_job_retry UNIQUE (check_in_id, retry_sequence),
    CONSTRAINT uk_roadmap_quiz_job_retry_key UNIQUE (owner_id, user_roadmap_node_id, retry_idempotency_key),
    CONSTRAINT ck_roadmap_quiz_job_purpose
        CHECK (purpose IN ('NODE', 'DIAGNOSTIC', 'STAGE_GRADUATION')),
    CONSTRAINT ck_roadmap_quiz_job_status
        CHECK (status IN ('PENDING', 'LEASED', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_roadmap_quiz_job_retry_sequence CHECK (retry_sequence BETWEEN 0 AND 3),
    CONSTRAINT ck_roadmap_quiz_job_attempt_count CHECK (attempt_count BETWEEN 0 AND 3)
);

CREATE INDEX idx_roadmap_check_in_owner_node
    ON roadmap_node_check_ins (owner_id, node_id, created_at);
CREATE INDEX idx_roadmap_quiz_job_claim
    ON roadmap_quiz_generation_jobs (status, lease_until, created_at);
CREATE INDEX idx_roadmap_quiz_job_owner_node
    ON roadmap_quiz_generation_jobs (owner_id, node_id, created_at);

ALTER TABLE quizzes ADD COLUMN purpose VARCHAR(30);
ALTER TABLE quizzes ADD COLUMN roadmap_node_id VARCHAR(100);
ALTER TABLE quizzes
    ADD CONSTRAINT fk_quizzes_roadmap_node FOREIGN KEY (roadmap_node_id) REFERENCES roadmap_nodes (id);
ALTER TABLE quizzes
    ADD CONSTRAINT ck_quizzes_roadmap_purpose
        CHECK (purpose IS NULL OR purpose IN ('NODE', 'DIAGNOSTIC', 'STAGE_GRADUATION'));
ALTER TABLE quizzes
    ADD CONSTRAINT ck_quizzes_node_origin
        CHECK (
            (purpose IS NULL AND roadmap_node_id IS NULL)
            OR (purpose = 'NODE' AND roadmap_node_id IS NOT NULL
                AND material_id IS NULL AND task_id IS NULL AND lesson_id IS NULL)
            OR (purpose IN ('DIAGNOSTIC', 'STAGE_GRADUATION') AND roadmap_node_id IS NULL)
        );
CREATE INDEX idx_quizzes_owner_roadmap_node
    ON quizzes (owner_id, roadmap_node_id, created_at);

ALTER TABLE quiz_questions ADD COLUMN question_signature VARCHAR(64);
ALTER TABLE quiz_questions
    ADD CONSTRAINT uk_quiz_question_signature UNIQUE (quiz_id, question_signature);
