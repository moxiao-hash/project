ALTER TABLE quizzes ADD COLUMN user_roadmap_node_id VARCHAR(36);
ALTER TABLE quizzes DROP CONSTRAINT ck_quizzes_purpose_origin;

UPDATE quizzes
SET user_roadmap_id = (
        SELECT jobs.user_roadmap_id FROM roadmap_quiz_generation_jobs jobs
        WHERE jobs.quiz_id = quizzes.id
        ORDER BY jobs.created_at DESC LIMIT 1
    ),
    user_roadmap_node_id = (
        SELECT jobs.user_roadmap_node_id FROM roadmap_quiz_generation_jobs jobs
        WHERE jobs.quiz_id = quizzes.id
        ORDER BY jobs.created_at DESC LIMIT 1
    ),
    roadmap_template_id = (
        SELECT roadmaps.template_id FROM user_roadmaps roadmaps
        WHERE roadmaps.id = (
            SELECT jobs.user_roadmap_id FROM roadmap_quiz_generation_jobs jobs
            WHERE jobs.quiz_id = quizzes.id
            ORDER BY jobs.created_at DESC LIMIT 1
        )
    )
WHERE purpose = 'NODE'
  AND EXISTS (SELECT 1 FROM roadmap_quiz_generation_jobs jobs WHERE jobs.quiz_id = quizzes.id);

ALTER TABLE quizzes
    ADD CONSTRAINT fk_quizzes_node_state_scope
        FOREIGN KEY (user_roadmap_node_id, owner_id, user_roadmap_id, roadmap_node_id)
        REFERENCES user_roadmap_nodes (id, owner_id, user_roadmap_id, node_id);
ALTER TABLE quizzes
    ADD CONSTRAINT fk_quizzes_node_template_scope
        FOREIGN KEY (roadmap_node_id, roadmap_template_id)
        REFERENCES roadmap_nodes (id, template_id);

ALTER TABLE quiz_questions ADD COLUMN points INT;
ALTER TABLE quiz_questions ADD COLUMN coverage_node_id VARCHAR(100);
ALTER TABLE quiz_questions ADD COLUMN practical BOOLEAN;

ALTER TABLE quizzes
    ADD CONSTRAINT ck_quizzes_purpose_origin
        CHECK (
            (purpose IS NULL AND roadmap_node_id IS NULL
                AND user_roadmap_id IS NULL AND user_roadmap_node_id IS NULL
                AND roadmap_stage_id IS NULL AND roadmap_template_id IS NULL)
            OR (purpose = 'NODE' AND roadmap_node_id IS NOT NULL
                AND roadmap_stage_id IS NULL
                AND material_id IS NULL AND task_id IS NULL AND lesson_id IS NULL
                AND ((user_roadmap_id IS NULL AND user_roadmap_node_id IS NULL
                        AND roadmap_template_id IS NULL)
                    OR (user_roadmap_id IS NOT NULL AND user_roadmap_node_id IS NOT NULL
                        AND roadmap_template_id IS NOT NULL)))
            OR (purpose = 'DIAGNOSTIC' AND roadmap_node_id IS NULL
                AND user_roadmap_id IS NOT NULL AND user_roadmap_node_id IS NULL
                AND roadmap_stage_id IS NULL AND roadmap_template_id IS NULL
                AND material_id IS NULL AND task_id IS NULL AND lesson_id IS NULL)
            OR (purpose = 'STAGE_GRADUATION' AND roadmap_node_id IS NULL
                AND user_roadmap_id IS NOT NULL AND user_roadmap_node_id IS NULL
                AND roadmap_stage_id IS NOT NULL AND roadmap_template_id IS NOT NULL
                AND material_id IS NULL AND task_id IS NULL AND lesson_id IS NULL)
        );
