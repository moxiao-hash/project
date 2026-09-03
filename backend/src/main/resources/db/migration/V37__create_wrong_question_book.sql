ALTER TABLE quizzes ADD COLUMN quiz_kind VARCHAR(30) NOT NULL DEFAULT 'GENERATED';

CREATE TABLE wrong_question_entries (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    source_question_id VARCHAR(36) NOT NULL,
    source_quiz_id VARCHAR(36) NOT NULL,
    chapter_key VARCHAR(180) NOT NULL,
    chapter_title VARCHAR(240) NOT NULL,
    status VARCHAR(20) NOT NULL,
    wrong_count INT NOT NULL,
    redo_count INT NOT NULL,
    latest_answer_json TEXT NOT NULL,
    first_wrong_at TIMESTAMP(6) NOT NULL,
    last_wrong_at TIMESTAMP(6) NOT NULL,
    mastered_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_wrong_question_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_wrong_question_source FOREIGN KEY (source_question_id) REFERENCES quiz_questions (id),
    CONSTRAINT fk_wrong_question_quiz FOREIGN KEY (source_quiz_id) REFERENCES quizzes (id),
    CONSTRAINT uk_wrong_question_owner_source UNIQUE (owner_id, source_question_id)
);

CREATE TABLE wrong_question_events (
    id VARCHAR(36) PRIMARY KEY,
    entry_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    question_id VARCHAR(36) NOT NULL,
    correct BOOLEAN NOT NULL,
    answer_json TEXT NOT NULL,
    score DOUBLE,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_wrong_event_entry FOREIGN KEY (entry_id) REFERENCES wrong_question_entries (id),
    CONSTRAINT fk_wrong_event_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_wrong_event_attempt FOREIGN KEY (attempt_id) REFERENCES quiz_attempts (id),
    CONSTRAINT fk_wrong_event_question FOREIGN KEY (question_id) REFERENCES quiz_questions (id),
    CONSTRAINT uk_wrong_event_attempt_question UNIQUE (attempt_id, question_id)
);

CREATE INDEX idx_wrong_question_owner_status
    ON wrong_question_entries (owner_id, status, wrong_count, last_wrong_at);

CREATE TABLE wrong_question_reviews (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    quiz_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL,
    chapter_key VARCHAR(180),
    status VARCHAR(20) NOT NULL,
    question_count INT NOT NULL,
    cleared_count INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    CONSTRAINT fk_wrong_review_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_wrong_review_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes (id),
    CONSTRAINT uk_wrong_review_owner_key UNIQUE (owner_id, idempotency_key)
);

CREATE TABLE wrong_question_review_items (
    id VARCHAR(36) PRIMARY KEY,
    review_id VARCHAR(36) NOT NULL,
    entry_id VARCHAR(36) NOT NULL,
    review_question_id VARCHAR(36) NOT NULL,
    CONSTRAINT fk_wrong_review_item_review FOREIGN KEY (review_id) REFERENCES wrong_question_reviews (id),
    CONSTRAINT fk_wrong_review_item_entry FOREIGN KEY (entry_id) REFERENCES wrong_question_entries (id),
    CONSTRAINT fk_wrong_review_item_question FOREIGN KEY (review_question_id) REFERENCES quiz_questions (id),
    CONSTRAINT uk_wrong_review_item_question UNIQUE (review_question_id)
);
