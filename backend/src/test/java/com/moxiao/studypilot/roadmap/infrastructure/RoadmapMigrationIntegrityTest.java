package com.moxiao.studypilot.roadmap.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoadmapMigrationIntegrityTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void createSchemaFromMigration() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:roadmap_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                true
        );
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE app_users (id VARCHAR(36) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE lessons (id VARCHAR(80) PRIMARY KEY)");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V24__create_roadmap_foundation.sql")
        ).execute(dataSource);

        insertUser("owner-1");
        insertUser("owner-2");
        insertTemplate("template-1", "roadmap", 1);
        insertTemplate("template-2", "roadmap", 2);
        insertTemplate("template-3", "roadmap", 3);
        insertTemplate("template-4", "roadmap", 4);
        insertStage("stage-1", "template-1", 1);
        insertStage("stage-2", "template-2", 1);
        insertNode("node-1", "template-1", "stage-1", 1);
        insertNode("node-2", "template-2", "stage-2", 1);
    }

    @Test
    void rejectsNodeWhoseStageBelongsToAnotherTemplate() {
        assertThatCode(() -> insertNode("node-valid", "template-1", "stage-1", 2))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> insertNode("node-invalid", "template-2", "stage-1", 3))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCrossTemplatePrerequisiteAndLegacyMapping() {
        jdbc.update("INSERT INTO lessons (id) VALUES (?)", "lesson-1");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO roadmap_node_prerequisites
                    (id, template_id, node_id, prerequisite_node_id)
                VALUES (?, ?, ?, ?)
                """, "prerequisite-1", "template-1", "node-1", "node-2"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO legacy_lesson_roadmap_mappings (lesson_id, template_id, node_id)
                VALUES (?, ?, ?)
                """, "lesson-1", "template-1", "node-2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesSingleCurrentRoadmapWhileAllowingMultipleInactiveRoadmaps() {
        insertUserRoadmap("roadmap-1", "owner-1", "template-1", "ACTIVE", "CURRENT");

        assertThatThrownBy(() -> insertUserRoadmap(
                "roadmap-2", "owner-1", "template-2", "ACTIVE", "CURRENT"
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> {
            insertUserRoadmap("roadmap-3", "owner-1", "template-3", "SUPERSEDED", null);
            insertUserRoadmap("roadmap-4", "owner-1", "template-4", "ARCHIVED", null);
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsStatusAndActiveSlotMismatch() {
        assertThatThrownBy(() -> insertUserRoadmap(
                "roadmap-1", "owner-1", "template-1", "ACTIVE", null
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUserRoadmap(
                "roadmap-2", "owner-2", "template-2", "SUPERSEDED", "CURRENT"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUserNodeFromAnotherOwnerOrTemplate() {
        insertUserRoadmap("roadmap-1", "owner-1", "template-1", "ACTIVE", "CURRENT");

        assertThatThrownBy(() -> insertUserRoadmapNode(
                "user-node-1", "roadmap-1", "node-1", "owner-2", "template-1"
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUserRoadmapNode(
                "user-node-2", "roadmap-1", "node-2", "owner-1", "template-2"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsOwnerMismatchForUpgradeAndLegacyEvidence() {
        insertUserRoadmap("roadmap-1", "owner-1", "template-1", "ACTIVE", "CURRENT");
        insertUserRoadmapNode("user-node-1", "roadmap-1", "node-1", "owner-1", "template-1");
        jdbc.update("INSERT INTO lessons (id) VALUES (?)", "lesson-1");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO roadmap_upgrades
                    (id, owner_id, user_roadmap_id, target_template_id, status, diff_json,
                     idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, "upgrade-1", "owner-2", "roadmap-1", "template-2", "PREVIEW", "{}", "key"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO legacy_learning_evidence
                    (id, owner_id, user_roadmap_node_id, lesson_id, original_status,
                     evidence_json, migration_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, "evidence-1", "owner-2", "user-node-1", "lesson-1", "COMPLETED", "{}", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void legacyEvidenceIdempotencyConstraintKeepsItsPortableNameAndColumnOrder() {
        assertThat(jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.key_column_usage
                WHERE table_name = 'legacy_learning_evidence'
                  AND constraint_name = 'uk_legacy_evidence_migration'
                ORDER BY ordinal_position
                """, String.class))
                .containsExactly("owner_id", "lesson_id", "migration_version");
    }

    private void insertUser(String id) {
        jdbc.update("INSERT INTO app_users (id) VALUES (?)", id);
    }

    private void insertTemplate(String id, String code, int version) {
        jdbc.update("""
                INSERT INTO roadmap_templates
                    (id, roadmap_code, template_version, title, description, publication_status,
                     content_checksum, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, code, version, "Title", "Description", "PUBLISHED", "checksum");
    }

    private void insertStage(String id, String templateId, int order) {
        jdbc.update("""
                INSERT INTO roadmap_stages
                    (id, template_id, stage_code, stage_order, title, description,
                     graduation_project_title)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, templateId, id, order, "Stage", "Description", "Project");
    }

    private void insertNode(String id, String templateId, String stageId, int order) {
        jdbc.update("""
                INSERT INTO roadmap_nodes
                    (id, template_id, stage_id, node_code, node_order, title, objectives_json,
                     high_frequency_json, common_mistakes_json, search_keywords_json,
                     artifact_requirement_json, quiz_blueprint_json, estimated_minutes,
                     practice_minutes, difficulty, required_node)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, templateId, stageId, id, order, "Node", "[]", "[]", "[]", "[]",
                "{}", "{}", 30, 20, "BEGINNER", true);
    }

    private void insertUserRoadmap(
            String id,
            String ownerId,
            String templateId,
            String status,
            String activeSlot
    ) {
        jdbc.update("""
                INSERT INTO user_roadmaps
                    (id, owner_id, template_id, status, active_slot, enrolled_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, ownerId, templateId, status, activeSlot);
    }

    private void insertUserRoadmapNode(
            String id,
            String userRoadmapId,
            String nodeId,
            String ownerId,
            String templateId
    ) {
        jdbc.update("""
                INSERT INTO user_roadmap_nodes
                    (id, user_roadmap_id, node_id, owner_id, template_id, availability_status,
                     learning_status, check_in_status, quiz_status, artifact_status,
                     completion_status, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, id, userRoadmapId, nodeId, ownerId, templateId, "AVAILABLE", "NOT_STARTED",
                "MISSING", "NOT_GENERATED", "NOT_REQUIRED", "INCOMPLETE");
    }
}
