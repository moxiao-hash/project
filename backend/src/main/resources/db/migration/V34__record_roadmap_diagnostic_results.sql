ALTER TABLE roadmap_diagnostics
    ADD COLUMN mastered_node_ids_json LONGTEXT NULL;

UPDATE roadmap_diagnostics
SET mastered_node_ids_json = '[]'
WHERE mastered_node_ids_json IS NULL;

ALTER TABLE roadmap_diagnostics
    MODIFY COLUMN mastered_node_ids_json LONGTEXT NOT NULL;
