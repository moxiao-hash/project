ALTER TABLE quizzes ADD COLUMN user_roadmap_node_id VARCHAR(36);

UPDATE quizzes
SET user_roadmap_id = (
        SELECT jobs.user_roadmap_id FROM roadmap_quiz_generation_jobs jobs
        WHERE jobs.quiz_id = quizzes.id
    ),
    user_roadmap_node_id = (
        SELECT jobs.user_roadmap_node_id FROM roadmap_quiz_generation_jobs jobs
        WHERE jobs.quiz_id = quizzes.id
    ),
    roadmap_template_id = (
        SELECT roadmaps.template_id FROM user_roadmaps roadmaps
        WHERE roadmaps.id = (
            SELECT jobs.user_roadmap_id FROM roadmap_quiz_generation_jobs jobs
            WHERE jobs.quiz_id = quizzes.id
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
