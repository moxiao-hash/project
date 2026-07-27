ALTER TABLE quizzes ADD COLUMN task_id VARCHAR(36);

ALTER TABLE quiz_questions ADD COLUMN difficulty VARCHAR(20) NOT NULL DEFAULT 'EASY';
ALTER TABLE quiz_questions ADD COLUMN coding_kind VARCHAR(30);
ALTER TABLE quiz_questions ADD COLUMN language VARCHAR(30);
ALTER TABLE quiz_questions ADD COLUMN starter_code TEXT;
ALTER TABLE quiz_questions ADD COLUMN rubric_json TEXT;
ALTER TABLE quiz_questions ADD COLUMN reference_answer TEXT;

CREATE TABLE quiz_question_sources (
    question_id VARCHAR(36) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    material_id VARCHAR(36),
    web_result_id VARCHAR(36),
    title VARCHAR(500) NOT NULL,
    locator VARCHAR(500),
    snippet TEXT NOT NULL,
    CONSTRAINT fk_quiz_source_question
        FOREIGN KEY (question_id) REFERENCES quiz_questions (id)
);

CREATE INDEX idx_quiz_source_question ON quiz_question_sources (question_id);
CREATE INDEX idx_quizzes_task ON quizzes (task_id, created_at);
