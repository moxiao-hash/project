ALTER TABLE quizzes ADD COLUMN lesson_id VARCHAR(80) NULL;

ALTER TABLE quizzes
    ADD CONSTRAINT fk_quizzes_lesson
        FOREIGN KEY (lesson_id) REFERENCES lessons (id);

CREATE INDEX idx_quizzes_owner_lesson ON quizzes (owner_id, lesson_id);

ALTER TABLE lesson_progress
    ADD COLUMN checkpoint_passed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE lesson_progress
    ADD COLUMN quiz_passed BOOLEAN NOT NULL DEFAULT FALSE;
