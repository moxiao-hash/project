ALTER TABLE user_roadmap_nodes
    ADD COLUMN diagnostic_mastered BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE roadmap_quiz_generation_jobs
    MODIFY check_in_id VARCHAR(36) NULL;
