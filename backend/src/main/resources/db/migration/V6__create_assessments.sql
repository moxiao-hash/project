CREATE TABLE quizzes (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    material_id VARCHAR(36),
    title VARCHAR(160) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_quizzes_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_quizzes_material
        FOREIGN KEY (material_id) REFERENCES materials (id)
);

CREATE TABLE quiz_questions (
    id VARCHAR(36) PRIMARY KEY,
    quiz_id VARCHAR(36) NOT NULL,
    question_position INT NOT NULL,
    type VARCHAR(30) NOT NULL,
    knowledge_point VARCHAR(180) NOT NULL,
    question_text TEXT NOT NULL,
    explanation TEXT NOT NULL,
    CONSTRAINT fk_questions_quiz
        FOREIGN KEY (quiz_id) REFERENCES quizzes (id)
);

CREATE TABLE question_options (
    question_id VARCHAR(36) NOT NULL,
    position INT NOT NULL,
    option_text VARCHAR(500) NOT NULL,
    PRIMARY KEY (question_id, position),
    CONSTRAINT fk_question_options_question
        FOREIGN KEY (question_id) REFERENCES quiz_questions (id)
);

CREATE TABLE question_correct_answers (
    question_id VARCHAR(36) NOT NULL,
    answer_text VARCHAR(500) NOT NULL,
    PRIMARY KEY (question_id, answer_text),
    CONSTRAINT fk_correct_answers_question
        FOREIGN KEY (question_id) REFERENCES quiz_questions (id)
);

CREATE TABLE quiz_attempts (
    id VARCHAR(36) PRIMARY KEY,
    quiz_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    score DOUBLE NOT NULL,
    answers_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_quiz_attempts_quiz
        FOREIGN KEY (quiz_id) REFERENCES quizzes (id),
    CONSTRAINT fk_quiz_attempts_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE TABLE mastery_records (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    knowledge_point VARCHAR(180) NOT NULL,
    score DOUBLE NOT NULL,
    attempt_count INT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_mastery_owner
        FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT uk_mastery_owner_point UNIQUE (owner_id, knowledge_point)
);

CREATE INDEX idx_quizzes_owner ON quizzes (owner_id, created_at);
CREATE INDEX idx_questions_quiz ON quiz_questions (quiz_id, question_position);
CREATE INDEX idx_mastery_owner_score ON mastery_records (owner_id, score);
