ALTER TABLE roadmap_quiz_generation_jobs ADD COLUMN lease_token VARCHAR(36);

ALTER TABLE user_roadmap_nodes
    ADD CONSTRAINT uk_user_roadmap_node_full_scope
        UNIQUE (id, owner_id, user_roadmap_id, node_id);
ALTER TABLE roadmap_node_check_ins
    ADD CONSTRAINT fk_roadmap_check_in_full_scope
        FOREIGN KEY (user_roadmap_node_id, owner_id, user_roadmap_id, node_id)
        REFERENCES user_roadmap_nodes (id, owner_id, user_roadmap_id, node_id);
ALTER TABLE roadmap_quiz_generation_jobs
    ADD CONSTRAINT fk_roadmap_quiz_job_full_scope
        FOREIGN KEY (user_roadmap_node_id, owner_id, user_roadmap_id, node_id)
        REFERENCES user_roadmap_nodes (id, owner_id, user_roadmap_id, node_id);

ALTER TABLE quizzes ADD COLUMN user_roadmap_id VARCHAR(36);
ALTER TABLE quizzes ADD COLUMN roadmap_stage_id VARCHAR(80);
ALTER TABLE quizzes
    ADD CONSTRAINT fk_quizzes_user_roadmap_scope
        FOREIGN KEY (user_roadmap_id, owner_id) REFERENCES user_roadmaps (id, owner_id);
ALTER TABLE quizzes
    ADD CONSTRAINT fk_quizzes_roadmap_stage
        FOREIGN KEY (roadmap_stage_id) REFERENCES roadmap_stages (id);
ALTER TABLE quizzes DROP CONSTRAINT ck_quizzes_node_origin;
ALTER TABLE quizzes
    ADD CONSTRAINT ck_quizzes_purpose_origin
        CHECK (
            (purpose IS NULL AND roadmap_node_id IS NULL
                AND user_roadmap_id IS NULL AND roadmap_stage_id IS NULL)
            OR (purpose IS NOT NULL AND purpose = 'NODE' AND roadmap_node_id IS NOT NULL
                AND user_roadmap_id IS NULL AND roadmap_stage_id IS NULL
                AND material_id IS NULL AND task_id IS NULL AND lesson_id IS NULL)
            OR (purpose IS NOT NULL AND purpose = 'DIAGNOSTIC' AND roadmap_node_id IS NULL
                AND user_roadmap_id IS NOT NULL AND roadmap_stage_id IS NULL
                AND material_id IS NULL AND task_id IS NULL AND lesson_id IS NULL)
            OR (purpose IS NOT NULL AND purpose = 'STAGE_GRADUATION' AND roadmap_node_id IS NULL
                AND user_roadmap_id IS NOT NULL AND roadmap_stage_id IS NOT NULL
                AND material_id IS NULL AND task_id IS NULL AND lesson_id IS NULL)
        );
