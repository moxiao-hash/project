package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.domain.RoadmapPublicationStatus;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapModuleEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapModuleJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "studypilot.roadmap.catalog-import-enabled=false")
@Transactional
class RoadmapModulePersistenceTest {

    @Autowired
    private RoadmapTemplateJpaRepository templateRepository;

    @Autowired
    private RoadmapStageJpaRepository stageRepository;

    @Autowired
    private RoadmapModuleJpaRepository moduleRepository;

    private JdbcTemplate migratedJdbc;

    @BeforeEach
    void createSchemaFromMigrations() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:roadmap_modules_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                true
        );
        migratedJdbc = new JdbcTemplate(dataSource);
        migratedJdbc.execute("CREATE TABLE app_users (id VARCHAR(36) PRIMARY KEY)");
        migratedJdbc.execute("CREATE TABLE lessons (id VARCHAR(80) PRIMARY KEY)");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V24__create_roadmap_foundation.sql"),
                new ClassPathResource("db/migration/V25__add_roadmap_modules.sql")
        ).execute(dataSource);

        insertTemplate("template-v1", "roadmap", 1);
        insertTemplate("template-v2", "roadmap", 2);
        insertStage("stage-v1", "template-v1", 1);
        insertStage("stage-v2-a", "template-v2", 1);
        insertStage("stage-v2-b", "template-v2", 2);
    }

    @Test
    void modulesAreScopedToTheirTemplateAndStage() {
        assertThatCode(() -> insertModule(
                "module-valid", "template-v2", "stage-v2-a", "basics", 1
        )).doesNotThrowAnyException();

        assertThatThrownBy(() -> insertModule(
                "module-cross-template", "template-v1", "stage-v2-a", "invalid", 2
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void moduleCodesAndOrdersAreUniqueWithinTheirCatalogScopes() {
        insertModule("module-1", "template-v2", "stage-v2-a", "basics", 1);

        assertThatThrownBy(() -> insertModule(
                "module-duplicate-code", "template-v2", "stage-v2-b", "basics", 1
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertModule(
                "module-duplicate-order", "template-v2", "stage-v2-a", "collections", 1
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repositoryReturnsModulesInModuleOrder() {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        templateRepository.save(new RoadmapTemplateEntity(
                "repository-template", "repository-roadmap", 2, "Roadmap", "Description",
                RoadmapPublicationStatus.DRAFT, "checksum", now
        ));
        stageRepository.save(new RoadmapStageEntity(
                "repository-stage", "repository-template", "stage", 1,
                "Stage", "Description", "Project"
        ));
        moduleRepository.saveAll(List.of(
                new RoadmapModuleEntity(
                        "repository-module-2", "repository-template", "repository-stage",
                        "second", 2, "Second", "Description"
                ),
                new RoadmapModuleEntity(
                        "repository-module-1", "repository-template", "repository-stage",
                        "first", 1, "First", "Description"
                )
        ));

        assertThat(moduleRepository.findAllByStageIdAndTemplateIdOrderByModuleOrderAsc(
                "repository-stage", "repository-template"
        )).extracting(RoadmapModuleEntity::getModuleCode)
                .containsExactly("first", "second");
    }

    @Test
    void v2NodesRequireAValidModuleInTheirTemplate() {
        insertModule("module-v2", "template-v2", "stage-v2-a", "basics", 1);

        assertThatCode(() -> insertNode(
                "node-v2", "template-v2", "stage-v2-a", "module-v2"
        )).doesNotThrowAnyException();
        assertThatThrownBy(() -> insertNode(
                "node-cross-template", "template-v1", "stage-v1", "module-v2"
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatNullPointerException().isThrownBy(() -> v2Node(null));
        assertThat(v2Node("module-v2").getModuleId()).isEqualTo("module-v2");
    }

    @Test
    void v1NodesRemainCompatibleWithoutAModule() {
        assertThatCode(() -> insertNode(
                "node-v1", "template-v1", "stage-v1", null
        )).doesNotThrowAnyException();

        RoadmapNodeEntity legacyNode = new RoadmapNodeEntity(
                "legacy-node", "template-v1", "stage-v1", "legacy", 1, "Legacy",
                "[]", "[]", "[]", "[]", "{}", "{}", 30, 20, "BASIC", true
        );
        assertThat(legacyNode.getModuleId()).isNull();
    }

    private RoadmapNodeEntity v2Node(String moduleId) {
        return new RoadmapNodeEntity(
                "v2-node", "template-v2", "stage-v2-a", moduleId, "v2", 1, "V2",
                "[]", "[]", "[]", "[]", "{}", "{}", 30, 20, "BASIC", true
        );
    }

    private void insertTemplate(String id, String roadmapCode, int version) {
        migratedJdbc.update("""
                INSERT INTO roadmap_templates
                    (id, roadmap_code, template_version, title, description, publication_status,
                     content_checksum, created_at, updated_at)
                VALUES (?, ?, ?, 'Roadmap', 'Description', 'DRAFT', 'checksum',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, roadmapCode, version);
    }

    private void insertStage(String id, String templateId, int stageOrder) {
        migratedJdbc.update("""
                INSERT INTO roadmap_stages
                    (id, template_id, stage_code, stage_order, title, description,
                     graduation_project_title)
                VALUES (?, ?, ?, ?, 'Stage', 'Description', 'Project')
                """, id, templateId, id, stageOrder);
    }

    private void insertModule(
            String id,
            String templateId,
            String stageId,
            String moduleCode,
            int moduleOrder
    ) {
        migratedJdbc.update("""
                INSERT INTO roadmap_modules
                    (id, template_id, stage_id, module_code, module_order, title, description)
                VALUES (?, ?, ?, ?, ?, 'Module', 'Description')
                """, id, templateId, stageId, moduleCode, moduleOrder);
    }

    private void insertNode(String id, String templateId, String stageId, String moduleId) {
        migratedJdbc.update("""
                INSERT INTO roadmap_nodes
                    (id, template_id, stage_id, module_id, node_code, node_order, title,
                     objectives_json, high_frequency_json, common_mistakes_json,
                     search_keywords_json, artifact_requirement_json, quiz_blueprint_json,
                     estimated_minutes, practice_minutes, difficulty, required_node)
                VALUES (?, ?, ?, ?, ?, 1, 'Node', '[]', '[]', '[]', '[]', '{}', '{}',
                        30, 20, 'BASIC', TRUE)
                """, id, templateId, stageId, moduleId, id);
    }
}
