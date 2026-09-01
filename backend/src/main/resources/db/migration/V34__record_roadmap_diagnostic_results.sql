ALTER TABLE roadmap_diagnostics
    ADD COLUMN mastered_node_ids_json LONGTEXT NOT NULL DEFAULT '[]';
