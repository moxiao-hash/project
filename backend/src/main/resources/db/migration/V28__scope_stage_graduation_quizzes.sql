ALTER TABLE quizzes ADD COLUMN roadmap_template_id VARCHAR(36);

UPDATE quizzes
SET roadmap_template_id = (
    SELECT user_roadmaps.template_id
    FROM user_roadmaps
    WHERE user_roadmaps.id = quizzes.user_roadmap_id
)
WHERE purpose = 'STAGE_GRADUATION';

ALTER TABLE quizzes DROP CONSTRAINT ck_quizzes_purpose_origin;
ALTER TABLE quizzes DROP CONSTRAINT fk_quizzes_roadmap_stage;
ALTER TABLE quizzes
    ADD CONSTRAINT fk_quizzes_user_roadmap_template_scope
        FOREIGN KEY (user_roadmap_id, roadmap_template_id)
        REFERENCES user_roadmaps (id, template_id);
ALTER TABLE quizzes
    ADD CONSTRAINT fk_quizzes_stage_template_scope
        FOREIGN KEY (roadmap_stage_id, roadmap_template_id)
        REFERENCES roadmap_stages (id, template_id);
ALTER TABLE quizzes
    ADD CONSTRAINT ck_quizzes_purpose_origin
        CHECK (
            (purpose IS NULL AND roadmap_node_id IS NULL
                AND user_roadmap_id IS NULL AND roadmap_stage_id IS NULL
                AND roadmap_template_id IS NULL)
            OR (purpose IS NOT NULL AND purpose = 'NODE' AND roadmap_node_id IS NOT NULL
                AND user_roadmap_id IS NULL AND roadmap_stage_id IS NULL
                AND roadmap_template_id IS NULL
                AND material_id IS NULL AND task_id IS NULL AND lesson_id IS NULL)
            OR (purpose IS NOT NULL AND purpose = 'DIAGNOSTIC' AND roadmap_node_id IS NULL
                AND user_roadmap_id IS NOT NULL AND roadmap_stage_id IS NULL
                AND roadmap_template_id IS NULL
                AND material_id IS NULL AND task_id IS NULL AND lesson_id IS NULL)
            OR (purpose IS NOT NULL AND purpose = 'STAGE_GRADUATION' AND roadmap_node_id IS NULL
                AND user_roadmap_id IS NOT NULL AND roadmap_stage_id IS NOT NULL
                AND roadmap_template_id IS NOT NULL
                AND material_id IS NULL AND task_id IS NULL AND lesson_id IS NULL)
        );
