package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.domain.RoadmapPublicationStatus;
import com.moxiao.studypilot.roadmap.application.RoadmapUpgradeService;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLessonRoadmapMappingEntity;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLessonRoadmapMappingJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapModuleEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapModuleJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapUpgradeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "studypilot.roadmap.catalog-import-enabled=false")
@AutoConfigureMockMvc
class RoadmapUpgradeWorkflowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoSpyBean RoadmapTemplateJpaRepository templateRepository;
    @Autowired RoadmapStageJpaRepository stageRepository;
    @Autowired RoadmapModuleJpaRepository moduleRepository;
    @Autowired LegacyLessonRoadmapMappingJpaRepository legacyMappingRepository;
    @Autowired RoadmapNodeJpaRepository nodeRepository;
    @Autowired RoadmapNodePrerequisiteJpaRepository prerequisiteRepository;
    @Autowired RoadmapUpgradeJpaRepository upgradeRepository;
    @Autowired UserRoadmapJpaRepository enrollmentRepository;
    @Autowired UserRoadmapNodeJpaRepository stateRepository;
    @Autowired RoadmapUpgradeService upgradeService;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;

    @Test
    void previewsOnlyTheLatestPublishedVersionAndRequiresManualReviewForContractChanges()
            throws Exception {
        Fixture fixture = fixture(true);
        String ownerToken = register("review-owner");
        String otherToken = register("review-other");
        enroll(ownerToken, fixture.roadmapCode(), 1);

        MvcResult result = mockMvc.perform(get("/api/roadmaps/current/upgrades")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceVersion").value(1))
                .andExpect(jsonPath("$[0].targetVersion").value(2))
                .andExpect(jsonPath("$[0].status").value("PREVIEW"))
                .andExpect(jsonPath("$[0].unchangedNodeCodes[0]").value("stable-node"))
                .andExpect(jsonPath("$[0].addedNodeCodes[0]").value("added-node"))
                .andExpect(jsonPath("$[0].removedNodeCodes[0]").value("removed-node"))
                .andExpect(jsonPath("$[0].manualReviewNodeCodes[0]").value("split-node"))
                .andExpect(jsonPath("$[0].manualReviewNodeCodes[1]").value("contract-node"))
                .andExpect(jsonPath("$[0].addedModuleCount").value(1))
                .andExpect(jsonPath("$[0].removedModuleCount").value(1))
                .andExpect(jsonPath("$[0].changedModuleCount").value(1))
                .andReturn();
        String upgradeId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get(0).get("id").asText();

        mockMvc.perform(get("/api/roadmaps/current/upgrades")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(upgradeId));
        assertThat(upgradeRepository.countByOwnerId(ownerId(ownerToken))).isEqualTo(1);

        mockMvc.perform(post("/api/roadmaps/current/upgrades/{id}/confirm", upgradeId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/roadmaps/current/upgrades/{id}/confirm", upgradeId)
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/roadmaps/current/upgrades")
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/roadmaps/current/upgrades"))
                .andExpect(status().isUnauthorized());
        assertThat(enrollmentRepository.findByOwnerIdAndActiveSlot(ownerId(ownerToken), "CURRENT"))
                .get().extracting(enrollment -> enrollment.getTemplateId())
                .isEqualTo(fixture.v1TemplateId());
    }

    @Test
    void confirmsASafeUpgradeOnceAndCarriesOnlyUnchangedCompletedNodes() throws Exception {
        Fixture fixture = fixture(false);
        String token = register("safe-owner");
        JsonNode oldEnrollment = enroll(token, fixture.roadmapCode(), 1);
        String oldEnrollmentId = oldEnrollment.get("id").asText();
        String oldStableId = nodeRepository.findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(
                        fixture.v1TemplateId()).stream()
                .filter(node -> node.getNodeCode().equals("stable-node"))
                .findFirst().orElseThrow().getId();
        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes
                SET availability_status = 'AVAILABLE', learning_status = 'IN_PROGRESS',
                    check_in_status = 'SUBMITTED', quiz_status = 'PASSED',
                    artifact_status = 'NOT_REQUIRED', completion_status = 'COMPLETED',
                    completed_at = CURRENT_TIMESTAMP
                WHERE user_roadmap_id = ? AND node_id = ?
                """, oldEnrollmentId, oldStableId);

        MvcResult previewResult = mockMvc.perform(get("/api/roadmaps/current/upgrades")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].manualReviewNodeCodes.length()").value(0))
                .andReturn();
        JsonNode preview = objectMapper.readTree(previewResult.getResponse().getContentAsString()).get(0);
        String upgradeId = preview.get("id").asText();

        MvcResult confirmedResult = mockMvc.perform(
                        post("/api/roadmaps/current/upgrades/{id}/confirm", upgradeId)
                                .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.id").value(upgradeId))
                .andReturn();
        String firstBody = confirmedResult.getResponse().getContentAsString();

        mockMvc.perform(post("/api/roadmaps/current/upgrades/{id}/confirm", upgradeId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .isEqualTo(firstBody));

        String ownerId = ownerId(token);
        var current = enrollmentRepository.findByOwnerIdAndActiveSlot(ownerId, "CURRENT").orElseThrow();
        assertThat(current.getTemplateId()).isEqualTo(fixture.v2TemplateId());
        assertThat(enrollmentRepository.findAllByOwnerIdAndStatus(
                ownerId, com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus.SUPERSEDED))
                .extracting(enrollment -> enrollment.getId()).containsExactly(oldEnrollmentId);
        assertThat(enrollmentRepository.findAllByOwnerIdAndStatus(
                ownerId, com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus.ACTIVE)).hasSize(1);
        assertThat(upgradeRepository.countByOwnerId(ownerId)).isEqualTo(1);

        var targetNodes = nodeRepository.findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(
                fixture.v2TemplateId());
        var states = stateRepository.findAllByUserRoadmapId(current.getId());
        String newStableId = targetNodes.stream().filter(node -> node.getNodeCode().equals("stable-node"))
                .findFirst().orElseThrow().getId();
        String addedId = targetNodes.stream().filter(node -> node.getNodeCode().equals("added-node"))
                .findFirst().orElseThrow().getId();
        assertThat(states).filteredOn(state -> state.getNodeId().equals(newStableId))
                .singleElement().extracting(state -> state.getCompletionStatus().name())
                .isEqualTo("COMPLETED");
        assertThat(states).filteredOn(state -> state.getNodeId().equals(newStableId))
                .singleElement().satisfies(state -> {
                    assertThat(state.getCheckInStatus().name()).isEqualTo("SUBMITTED");
                    assertThat(state.getQuizStatus().name()).isEqualTo("PASSED");
                    assertThat(state.getAvailabilityStatus().name()).isEqualTo("AVAILABLE");
                });
        assertThat(states).filteredOn(state -> state.getNodeId().equals(addedId))
                .singleElement().extracting(state -> state.getCompletionStatus().name())
                .isEqualTo("INCOMPLETE");
        assertThat(states).filteredOn(state -> targetNodes.stream()
                        .filter(node -> node.getNodeCode().equals("split-node"))
                        .anyMatch(node -> node.getId().equals(state.getNodeId())))
                .singleElement().extracting(state -> state.getAvailabilityStatus().name())
                .isEqualTo("AVAILABLE");
        assertThat(stateRepository.findAllByUserRoadmapId(oldEnrollmentId))
                .hasSize(4)
                .filteredOn(state -> state.getNodeId().equals(oldStableId))
                .singleElement().satisfies(state -> {
                    assertThat(state.getCompletionStatus().name()).isEqualTo("COMPLETED");
                    assertThat(state.getCheckInStatus().name()).isEqualTo("SUBMITTED");
                    assertThat(state.getQuizStatus().name()).isEqualTo("PASSED");
                    assertThat(state.getCompletedAt()).isNotNull();
                });
    }

    @Test
    void carriesCompletionOnlyAcrossAnExplicitLessonMappingWithFullyEquivalentContent()
            throws Exception {
        Fixture fixture = fixture(false);
        String token = register("explicit-mapping-owner");
        JsonNode enrollment = enroll(token, fixture.roadmapCode(), 1);
        String enrollmentId = enrollment.get("id").asText();
        RoadmapNodeEntity oldStable = node(fixture.v1TemplateId(), "stable-node");
        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes
                SET availability_status = 'AVAILABLE', learning_status = 'IN_PROGRESS',
                    check_in_status = 'SUBMITTED', quiz_status = 'PASSED',
                    artifact_status = 'NOT_REQUIRED', completion_status = 'COMPLETED',
                    completed_at = CURRENT_TIMESTAMP
                WHERE user_roadmap_id = ? AND node_id = ?
                """, enrollmentId, oldStable.getId());

        // Same code and assessment are insufficient when the teaching content has changed.
        jdbcTemplate.update("""
                UPDATE roadmap_nodes SET objectives_json = '["new objective"]'
                WHERE template_id = ? AND node_code = 'contract-node'
                """, fixture.v2TemplateId());

        mockMvc.perform(get("/api/roadmaps/current/upgrades")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unchangedNodeCodes[0]").value("stable-node"))
                .andExpect(jsonPath("$[0].manualReviewNodeCodes").value(
                        org.hamcrest.Matchers.hasItem("contract-node")));
    }

    @Test
    void doesNotFanOutACompletedBroadNodeToGranularV2Nodes() throws Exception {
        GranularFixture fixture = granularFixture();
        String token = register("granular-owner");
        JsonNode enrollment = enroll(token, fixture.roadmapCode(), 1);
        String oldEnrollmentId = enrollment.get("id").asText();
        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes
                SET availability_status = 'AVAILABLE', learning_status = 'IN_PROGRESS',
                    check_in_status = 'SUBMITTED', quiz_status = 'PASSED',
                    artifact_status = 'NOT_REQUIRED', completion_status = 'COMPLETED',
                    completed_at = CURRENT_TIMESTAMP
                WHERE user_roadmap_id = ?
                """, oldEnrollmentId);

        String ownerId = ownerId(token);
        String upgradeId = upgradeService.previews(ownerId).get(0).id();
        upgradeService.confirm(ownerId, upgradeId);

        var current = enrollmentRepository.findByOwnerIdAndActiveSlot(ownerId, "CURRENT").orElseThrow();
        assertThat(stateRepository.findAllByUserRoadmapId(current.getId()))
                .allSatisfy(state -> assertThat(state.getCompletionStatus())
                        .isEqualTo(com.moxiao.studypilot.roadmap.domain.CompletionStatus.INCOMPLETE));
        assertThat(stateRepository.findAllByUserRoadmapId(oldEnrollmentId))
                .singleElement().satisfies(state -> {
                    assertThat(state.getCompletionStatus())
                            .isEqualTo(com.moxiao.studypilot.roadmap.domain.CompletionStatus.COMPLETED);
                    assertThat(state.getQuizStatus().name()).isEqualTo("PASSED");
                });
    }

    @Test
    void carriesAnEquivalentRenamedNodeOnlyWhenOneLegacyLessonMapsThePair() throws Exception {
        GranularFixture fixture = equivalentRenameFixture();
        String token = register("renamed-mapping-owner");
        JsonNode enrollment = enroll(token, fixture.roadmapCode(), 1);
        String oldEnrollmentId = enrollment.get("id").asText();
        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes
                SET availability_status = 'AVAILABLE', learning_status = 'IN_PROGRESS',
                    check_in_status = 'SUBMITTED', quiz_status = 'PASSED',
                    artifact_status = 'NOT_REQUIRED', completion_status = 'COMPLETED',
                    completed_at = CURRENT_TIMESTAMP
                WHERE user_roadmap_id = ?
                """, oldEnrollmentId);

        String ownerId = ownerId(token);
        var preview = upgradeService.previews(ownerId).get(0);
        assertThat(preview.unchangedNodeCodes()).containsExactly("renamed-equivalent");
        upgradeService.confirm(ownerId, preview.id());

        var current = enrollmentRepository.findByOwnerIdAndActiveSlot(ownerId, "CURRENT").orElseThrow();
        assertThat(stateRepository.findAllByUserRoadmapId(current.getId()))
                .singleElement().satisfies(state -> {
                    assertThat(state.getCompletionStatus())
                            .isEqualTo(com.moxiao.studypilot.roadmap.domain.CompletionStatus.COMPLETED);
                    assertThat(state.getQuizStatus().name()).isEqualTo("PASSED");
                });
    }

    @Test
    void flagsAmbiguousLegacyMappingsInsteadOfFanningOutOneSourceCompletion() throws Exception {
        GranularFixture fixture = granularFixture();
        RoadmapNodeEntity source = node(fixture.v1TemplateId(), "java-syntax-oop");
        RoadmapNodeEntity firstTarget = node(fixture.v2TemplateId(), "variables-types-conversion");
        RoadmapNodeEntity secondTarget = node(fixture.v2TemplateId(), "conditions-if-switch");
        String suffix = UUID.randomUUID().toString();
        mapLesson("ambiguous-a-" + suffix, fixture.v1TemplateId(), source.getId());
        mapLesson("ambiguous-a-" + suffix, fixture.v2TemplateId(), firstTarget.getId());
        mapLesson("ambiguous-b-" + suffix, fixture.v1TemplateId(), source.getId());
        mapLesson("ambiguous-b-" + suffix, fixture.v2TemplateId(), secondTarget.getId());
        String token = register("ambiguous-mapping-owner");
        enroll(token, fixture.roadmapCode(), 1);

        mockMvc.perform(get("/api/roadmaps/current/upgrades")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unchangedNodeCodes.length()").value(0))
                .andExpect(jsonPath("$[0].manualReviewNodeCodes[0]")
                        .value("variables-types-conversion"))
                .andExpect(jsonPath("$[0].manualReviewNodeCodes[1]")
                        .value("conditions-if-switch"));
    }

    @Test
    void returnsNoPreviewWhenNoHigherPublishedVersionExists() throws Exception {
        Fixture fixture = fixture(false);
        templateRepository.save(new RoadmapTemplateEntity(
                "draft-" + UUID.randomUUID().toString().substring(0, 8), fixture.roadmapCode(), 3,
                "draft", "draft", RoadmapPublicationStatus.DRAFT, "3".repeat(64), Instant.now()));
        String token = register("latest-owner");
        enroll(token, fixture.roadmapCode(), 2);

        mockMvc.perform(get("/api/roadmaps/current/upgrades")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void treatsObjectOrderAndNumericallyEquivalentContractValuesAsUnchanged() throws Exception {
        Fixture fixture = fixture(false);
        jdbcTemplate.update("""
                UPDATE roadmap_nodes
                SET artifact_requirement_json = ?, quiz_blueprint_json = ?
                WHERE template_id = ? AND node_code = 'contract-node'
                """, "{\"required\":false,\"minimum\":1}",
                "[{\"type\":\"SINGLE_CHOICE\",\"weight\":1}]", fixture.v1TemplateId());
        jdbcTemplate.update("""
                UPDATE roadmap_nodes
                SET artifact_requirement_json = ?, quiz_blueprint_json = ?
                WHERE template_id = ? AND node_code = 'contract-node'
                """, "{\"minimum\":1.0,\"required\":false}",
                "[{\"weight\":1.00,\"type\":\"SINGLE_CHOICE\"}]", fixture.v2TemplateId());
        String token = register("canonical-owner");
        enroll(token, fixture.roadmapCode(), 1);

        mockMvc.perform(get("/api/roadmaps/current/upgrades")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].manualReviewNodeCodes.length()").value(0))
                .andExpect(jsonPath("$[0].unchangedNodeCodes[2]").value("contract-node"));
    }

    @Test
    void treatsQuizArrayOrderAsPartOfTheCompletionContract() throws Exception {
        Fixture fixture = fixture(false);
        jdbcTemplate.update("""
                UPDATE roadmap_nodes SET quiz_blueprint_json = ?
                WHERE template_id = ? AND node_code = 'contract-node'
                """, "[{\"type\":\"A\"},{\"type\":\"B\"}]", fixture.v1TemplateId());
        jdbcTemplate.update("""
                UPDATE roadmap_nodes SET quiz_blueprint_json = ?
                WHERE template_id = ? AND node_code = 'contract-node'
                """, "[{\"type\":\"B\"},{\"type\":\"A\"}]", fixture.v2TemplateId());
        String token = register("array-order-owner");
        enroll(token, fixture.roadmapCode(), 1);

        mockMvc.perform(get("/api/roadmaps/current/upgrades")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].manualReviewNodeCodes[0]").value("contract-node"));
    }

    @Test
    void rejectsAStalePreviewAfterANewerPublishedVersionAppears() throws Exception {
        Fixture fixture = fixture(false);
        String token = register("newer-owner");
        JsonNode oldEnrollment = enroll(token, fixture.roadmapCode(), 1);
        String ownerId = ownerId(token);
        String upgradeId = upgradeService.previews(ownerId).get(0).id();
        templateRepository.save(new RoadmapTemplateEntity(
                "v3-" + UUID.randomUUID().toString().substring(0, 8), fixture.roadmapCode(), 3,
                "v3", "v3", RoadmapPublicationStatus.PUBLISHED, "3".repeat(64), Instant.now()));

        mockMvc.perform(post("/api/roadmaps/current/upgrades/{id}/confirm", upgradeId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
        assertThat(enrollmentRepository.findByOwnerIdAndActiveSlot(ownerId, "CURRENT"))
                .get().extracting(enrollment -> enrollment.getId())
                .isEqualTo(oldEnrollment.get("id").asText());
        assertThat(upgradeRepository.findByOwnerIdAndId(ownerId, upgradeId))
                .get().extracting(upgrade -> upgrade.getStatus().name()).isEqualTo("PREVIEW");
    }

    @Test
    void rejectsATamperedTargetFromAnotherRoadmap() throws Exception {
        Fixture fixture = fixture(false);
        String token = register("scope-owner");
        JsonNode oldEnrollment = enroll(token, fixture.roadmapCode(), 1);
        String ownerId = ownerId(token);
        String upgradeId = upgradeService.previews(ownerId).get(0).id();
        String foreignTemplateId = "foreign-" + UUID.randomUUID().toString().substring(0, 8);
        templateRepository.save(new RoadmapTemplateEntity(
                foreignTemplateId, "foreign-" + UUID.randomUUID(), 99, "foreign", "foreign",
                RoadmapPublicationStatus.PUBLISHED, "f".repeat(64), Instant.now()));
        jdbcTemplate.update("UPDATE roadmap_upgrades SET target_template_id = ? WHERE id = ?",
                foreignTemplateId, upgradeId);

        mockMvc.perform(post("/api/roadmaps/current/upgrades/{id}/confirm", upgradeId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
        assertThat(enrollmentRepository.findByOwnerIdAndActiveSlot(ownerId, "CURRENT"))
                .get().extracting(enrollment -> enrollment.getId())
                .isEqualTo(oldEnrollment.get("id").asText());
    }

    @Test
    void refusesUnpublishedTargetsForPreviewAndConfirmation() throws Exception {
        Fixture previewFixture = fixture(false);
        jdbcTemplate.update("UPDATE roadmap_templates SET publication_status = 'DRAFT' WHERE id = ?",
                previewFixture.v2TemplateId());
        String previewToken = register("unpublished-preview");
        enroll(previewToken, previewFixture.roadmapCode(), 1);
        mockMvc.perform(get("/api/roadmaps/current/upgrades")
                        .header("Authorization", bearer(previewToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        Fixture confirmFixture = fixture(false);
        String confirmToken = register("unpublished-confirm");
        JsonNode oldEnrollment = enroll(confirmToken, confirmFixture.roadmapCode(), 1);
        String ownerId = ownerId(confirmToken);
        String upgradeId = upgradeService.previews(ownerId).get(0).id();
        jdbcTemplate.update("UPDATE roadmap_templates SET publication_status = 'DRAFT' WHERE id = ?",
                confirmFixture.v2TemplateId());

        mockMvc.perform(post("/api/roadmaps/current/upgrades/{id}/confirm", upgradeId)
                        .header("Authorization", bearer(confirmToken)))
                .andExpect(status().isConflict());
        assertThat(enrollmentRepository.findByOwnerIdAndActiveSlot(ownerId, "CURRENT"))
                .get().extracting(enrollment -> enrollment.getId())
                .isEqualTo(oldEnrollment.get("id").asText());
    }

    @Test
    void rollsBackSupersedeAndPreviewCompletionWhenTargetInitializationFails() throws Exception {
        Fixture fixture = fixture(false);
        String token = register("rollback-owner");
        JsonNode oldEnrollment = enroll(token, fixture.roadmapCode(), 1);
        String ownerId = ownerId(token);
        String upgradeId = upgradeService.previews(ownerId).get(0).id();
        jdbcTemplate.update("""
                UPDATE roadmap_nodes SET artifact_requirement_json = '{}'
                WHERE template_id = ? AND node_code = 'added-node'
                """, fixture.v2TemplateId());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> upgradeService.confirm(ownerId, upgradeId)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(enrollmentRepository.findByOwnerIdAndActiveSlot(ownerId, "CURRENT"))
                .get().extracting(enrollment -> enrollment.getId())
                .isEqualTo(oldEnrollment.get("id").asText());
        assertThat(enrollmentRepository.findAllByOwnerIdAndStatus(
                ownerId, com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus.ACTIVE)).hasSize(1);
        assertThat(upgradeRepository.findByOwnerIdAndId(ownerId, upgradeId))
                .get().extracting(upgrade -> upgrade.getStatus().name()).isEqualTo("PREVIEW");
    }

    @Test
    void serializesConcurrentPreviewCreationWithOneStableIdempotencyRecord() throws Exception {
        Fixture fixture = fixture(false);
        String token = register("preview-race-owner");
        enroll(token, fixture.roadmapCode(), 1);
        String ownerId = ownerId(token);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("preview start timed out");
                }
                return upgradeService.previews(ownerId).get(0);
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            String firstId = futures.get(0).get(10, TimeUnit.SECONDS).id();
            assertThat(futures.get(1).get(10, TimeUnit.SECONDS).id()).isEqualTo(firstId);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(upgradeRepository.countByOwnerId(ownerId)).isEqualTo(1);
    }

    @Test
    void neverCreatesAStalePreviewWhileTheTargetPublicationWriteIsPending() throws Exception {
        Fixture fixture = fixture(false);
        String token = register("publication-race-owner");
        enroll(token, fixture.roadmapCode(), 1);
        String ownerId = ownerId(token);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch targetUpdated = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch lockingQueryEntered = new CountDownLatch(1);
        CountDownLatch lockingQueryReturned = new CountDownLatch(1);
        doAnswer(invocation -> {
            lockingQueryEntered.countDown();
            try {
                return delegateToActualTemplateRepository(invocation);
            } finally {
                lockingQueryReturned.countDown();
            }
        }).when(templateRepository).findPublishedVersionsForUpgrade(any(), any());
        try {
            var publisher = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.update("""
                                UPDATE roadmap_templates SET publication_status = 'DRAFT'
                                WHERE id = ?
                                """, fixture.v2TemplateId());
                        targetUpdated.countDown();
                        try {
                            if (!allowCommit.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("publication commit timed out");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("publication interrupted", exception);
                        }
                    }));
            assertThat(targetUpdated.await(5, TimeUnit.SECONDS)).isTrue();
            var preview = executor.submit(() -> upgradeService.previews(ownerId));
            assertThat(lockingQueryEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(lockingQueryReturned.await(300, TimeUnit.MILLISECONDS)).isFalse();
            allowCommit.countDown();
            publisher.get(5, TimeUnit.SECONDS);
            assertThat(lockingQueryReturned.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(preview.get(10, TimeUnit.SECONDS)).isEmpty();
            assertThat(upgradeRepository.countByOwnerId(ownerId)).isZero();
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void retriesInANewTransactionWithoutMarkingTheOuterTransactionRollbackOnly() throws Exception {
        Fixture fixture = fixture(false);
        String token = register("outer-transaction-owner");
        enroll(token, fixture.roadmapCode(), 1);
        String ownerId = ownerId(token);
        String marker = "outer-committed-" + UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        Set<Object> attemptEntityManagers = Collections.newSetFromMap(new IdentityHashMap<>());
        AtomicReference<Object> outerEntityManager = new AtomicReference<>();
        clearInvocations(templateRepository);
        doAnswer(invocation -> {
            attemptEntityManagers.add(entityManager.getDelegate());
            if (calls.incrementAndGet() == 1) {
                throw new org.springframework.dao.CannotAcquireLockException("forced first attempt");
            }
            return delegateToActualTemplateRepository(invocation);
        }).when(templateRepository).findPublishedVersionsForUpgrade(any(), any());

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            outerEntityManager.set(entityManager.getDelegate());
            assertThat(upgradeService.previews(ownerId)).hasSize(1);
            assertThat(status.isRollbackOnly()).isFalse();
            assertThat(entityManager.getDelegate()).isSameAs(outerEntityManager.get());
            jdbcTemplate.update("UPDATE app_users SET display_name = ? WHERE id = ?", marker, ownerId);
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM app_users WHERE id = ?", String.class, ownerId))
                .isEqualTo(marker);
        assertThat(calls).hasValue(2);
        assertThat(attemptEntityManagers).hasSize(2).doesNotContain(outerEntityManager.get());
        assertThat(upgradeRepository.countByOwnerId(ownerId)).isEqualTo(1);
    }

    @Test
    void retriesOnlyLockAcquisitionFailuresAtMostThreeTimes() throws Exception {
        Fixture fixture = fixture(false);
        String token = register("bounded-retry-owner");
        enroll(token, fixture.roadmapCode(), 1);
        String ownerId = ownerId(token);
        clearInvocations(templateRepository);
        doThrow(new org.springframework.dao.CannotAcquireLockException("always locked"))
                .when(templateRepository).findPublishedVersionsForUpgrade(any(), any());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> upgradeService.previews(ownerId)))
                .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
        verify(templateRepository, times(3)).findPublishedVersionsForUpgrade(any(), any());
        assertThat(upgradeRepository.countByOwnerId(ownerId)).isZero();
    }

    private Object delegateToActualTemplateRepository(InvocationOnMock invocation) throws Throwable {
        // Spring wraps JDK repository proxies with Mockito's delegatesTo answer. Calling that answer
        // reaches the original Spring Data proxy, so repository metadata such as @Lock is exercised.
        return mockingDetails(templateRepository)
                .getMockCreationSettings()
                .getDefaultAnswer()
                .answer(invocation);
    }

    @Test
    void serializesConcurrentConfirmationAndReturnsOneCompletedUpgrade() throws Exception {
        Fixture fixture = fixture(false);
        String token = register("race-owner");
        enroll(token, fixture.roadmapCode(), 1);
        String ownerId = ownerId(token);
        String upgradeId = upgradeService.previews(ownerId).get(0).id();

        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("confirmation start timed out");
                }
                return upgradeService.confirm(ownerId, upgradeId);
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(futures.get(0).get(10, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            assertThat(futures.get(1).get(10, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(upgradeRepository.countByOwnerId(ownerId)).isEqualTo(1);
        assertThat(enrollmentRepository.findAllByOwnerIdAndStatus(
                ownerId, com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus.ACTIVE)).hasSize(1);
    }

    private Fixture fixture(boolean contractChange) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = "upgrade-" + suffix;
        String v1 = "v1-" + suffix;
        String v2 = "v2-" + suffix;
        Instant now = Instant.now();
        templateRepository.save(new RoadmapTemplateEntity(v1, code, 1, "v1", "v1",
                RoadmapPublicationStatus.PUBLISHED, "1".repeat(64), now));
        templateRepository.save(new RoadmapTemplateEntity(v2, code, 2, "v2", "v2",
                RoadmapPublicationStatus.PUBLISHED, "2".repeat(64), now));
        String s1 = "s1-" + suffix;
        String s2 = "s2-" + suffix;
        stageRepository.save(new RoadmapStageEntity(s1, v1, "stage", 1, "阶段", "描述", "项目"));
        stageRepository.save(new RoadmapStageEntity(s2, v2, "stage", 1, "阶段", "描述", "项目"));

        String oldCommonModule = "old-common-module-" + suffix;
        String oldRemovedModule = "old-removed-module-" + suffix;
        String newCommonModule = "new-common-module-" + suffix;
        String newAddedModule = "new-added-module-" + suffix;
        moduleRepository.save(new RoadmapModuleEntity(
                oldCommonModule, v1, s1, "common-module", 1, "旧标题", "共同描述"));
        moduleRepository.save(new RoadmapModuleEntity(
                oldRemovedModule, v1, s1, "removed-module", 2, "删除模块", "删除描述"));
        moduleRepository.save(new RoadmapModuleEntity(
                newCommonModule, v2, s2, "common-module", 1, "新标题", "共同描述"));
        moduleRepository.save(new RoadmapModuleEntity(
                newAddedModule, v2, s2, "added-module", 2, "新增模块", "新增描述"));

        saveNode(v1, s1, oldCommonModule, "old-stable-" + suffix, "stable-node", 1, false, "[]");
        saveNode(v1, s1, oldCommonModule, "old-split-" + suffix, "split-node", 2, false, "[]");
        saveNode(v1, s1, oldCommonModule, "old-contract-" + suffix, "contract-node", 3, false, "[]");
        saveNode(v1, s1, oldRemovedModule, "old-removed-" + suffix, "removed-node", 4, false, "[]");
        saveNode(v2, s2, newCommonModule, "new-stable-" + suffix, "stable-node", 1, false, "[]");
        saveNode(v2, s2, newCommonModule, "new-split-" + suffix, "split-node", 2, false, "[]");
        saveNode(v2, s2, newCommonModule, "new-contract-" + suffix, "contract-node", 3,
                contractChange, contractChange ? "[{\"type\":\"CODING\"}]" : "[]");
        saveNode(v2, s2, newAddedModule, "new-added-" + suffix, "added-node", 4, false, "[]");
        mapLesson("lesson-stable-" + suffix, v1, "old-stable-" + suffix);
        mapLesson("lesson-stable-" + suffix, v2, "new-stable-" + suffix);
        mapLesson("lesson-split-" + suffix, v1, "old-split-" + suffix);
        mapLesson("lesson-split-" + suffix, v2, "new-split-" + suffix);
        mapLesson("lesson-contract-" + suffix, v1, "old-contract-" + suffix);
        mapLesson("lesson-contract-" + suffix, v2, "new-contract-" + suffix);
        prerequisiteRepository.save(new RoadmapNodePrerequisiteEntity(
                "old-edge-" + suffix, v1, "old-split-" + suffix, "old-stable-" + suffix));
        prerequisiteRepository.save(new RoadmapNodePrerequisiteEntity(
                "new-edge-" + suffix, v2, "new-split-" + suffix,
                contractChange ? "new-added-" + suffix : "new-stable-" + suffix));
        return new Fixture(code, v1, v2);
    }

    private void saveNode(
            String templateId, String stageId, String moduleId, String id, String code, int order,
            boolean artifactRequired, String quizBlueprint
    ) {
        nodeRepository.save(new RoadmapNodeEntity(
                id, templateId, stageId, moduleId, code, order, code,
                "[]", "[]", "[]", "[]",
                "{\"required\":" + artifactRequired + "}", quizBlueprint,
                30, 15, "EASY", true));
    }

    private GranularFixture granularFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = "granular-" + suffix;
        String v1 = "broad-v1-" + suffix;
        String v2 = "fine-v2-" + suffix;
        Instant now = Instant.now();
        templateRepository.save(new RoadmapTemplateEntity(v1, code, 1, "v1", "v1",
                RoadmapPublicationStatus.PUBLISHED, "a".repeat(64), now));
        templateRepository.save(new RoadmapTemplateEntity(v2, code, 2, "v2", "v2",
                RoadmapPublicationStatus.PUBLISHED, "b".repeat(64), now));
        String s1 = "broad-stage-" + suffix;
        String s2 = "fine-stage-" + suffix;
        stageRepository.save(new RoadmapStageEntity(s1, v1, "java", 1, "Java", "描述", "项目"));
        stageRepository.save(new RoadmapStageEntity(s2, v2, "java", 1, "Java", "描述", "项目"));
        String oldModule = "broad-module-" + suffix;
        String newModule = "fine-module-" + suffix;
        moduleRepository.save(new RoadmapModuleEntity(
                oldModule, v1, s1, "java-basics", 1, "Java 综合", "宽泛内容"));
        moduleRepository.save(new RoadmapModuleEntity(
                newModule, v2, s2, "java-basics", 1, "Java 基础", "细粒度内容"));
        saveNode(v1, s1, oldModule, "broad-node-" + suffix, "java-syntax-oop", 1, false, "[]");
        saveNode(v2, s2, newModule, "fine-a-" + suffix, "variables-types-conversion", 1, false, "[]");
        saveNode(v2, s2, newModule, "fine-b-" + suffix, "conditions-if-switch", 2, false, "[]");
        prerequisiteRepository.save(new RoadmapNodePrerequisiteEntity(
                "fine-edge-" + suffix, v2, "fine-b-" + suffix, "fine-a-" + suffix));
        // A legacy lesson may select one candidate, but unequal content must not transfer completion.
        mapLesson("lesson-java-broad-" + suffix, v1, "broad-node-" + suffix);
        return new GranularFixture(code, v1, v2);
    }

    private GranularFixture equivalentRenameFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = "rename-" + suffix;
        String v1 = "rename-v1-" + suffix;
        String v2 = "rename-v2-" + suffix;
        Instant now = Instant.now();
        templateRepository.save(new RoadmapTemplateEntity(v1, code, 1, "v1", "v1",
                RoadmapPublicationStatus.PUBLISHED, "c".repeat(64), now));
        templateRepository.save(new RoadmapTemplateEntity(v2, code, 2, "v2", "v2",
                RoadmapPublicationStatus.PUBLISHED, "d".repeat(64), now));
        String s1 = "rename-stage-v1-" + suffix;
        String s2 = "rename-stage-v2-" + suffix;
        stageRepository.save(new RoadmapStageEntity(s1, v1, "java", 1, "Java", "描述", "项目"));
        stageRepository.save(new RoadmapStageEntity(s2, v2, "java", 1, "Java", "描述", "项目"));
        String m1 = "rename-module-v1-" + suffix;
        String m2 = "rename-module-v2-" + suffix;
        moduleRepository.save(new RoadmapModuleEntity(m1, v1, s1, "java", 1, "Java", "描述"));
        moduleRepository.save(new RoadmapModuleEntity(m2, v2, s2, "java", 1, "Java", "描述"));
        String oldNode = "old-equivalent-" + suffix;
        String newNode = "new-equivalent-" + suffix;
        saveNode(v1, s1, m1, oldNode, "legacy-equivalent", 1, false, "[]");
        saveNode(v2, s2, m2, newNode, "renamed-equivalent", 1, false, "[]");
        jdbcTemplate.update("UPDATE roadmap_nodes SET title = '等价节点' WHERE id IN (?, ?)",
                oldNode, newNode);
        mapLesson("lesson-equivalent-" + suffix, v1, oldNode);
        mapLesson("lesson-equivalent-" + suffix, v2, newNode);
        return new GranularFixture(code, v1, v2);
    }

    private RoadmapNodeEntity node(String templateId, String code) {
        return nodeRepository.findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(templateId).stream()
                .filter(node -> node.getNodeCode().equals(code)).findFirst().orElseThrow();
    }

    private void mapLesson(String lessonId, String templateId, String nodeId) {
        legacyMappingRepository.save(new LegacyLessonRoadmapMappingEntity(lessonId, templateId, nodeId));
    }

    private String register(String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "升级用户"
                                }
                                """.formatted(label, System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private JsonNode enroll(String token, String roadmapCode, int version) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roadmapCode":"%s","templateVersion":%d}
                                """.formatted(roadmapCode, version)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String ownerId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record Fixture(String roadmapCode, String v1TemplateId, String v2TemplateId) { }
    private record GranularFixture(String roadmapCode, String v1TemplateId, String v2TemplateId) { }
}
