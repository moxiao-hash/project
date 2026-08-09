package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.domain.RoadmapPublicationStatus;
import com.moxiao.studypilot.roadmap.application.RoadmapUpgradeService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Autowired RoadmapTemplateJpaRepository templateRepository;
    @Autowired RoadmapStageJpaRepository stageRepository;
    @Autowired RoadmapNodeJpaRepository nodeRepository;
    @Autowired RoadmapNodePrerequisiteJpaRepository prerequisiteRepository;
    @Autowired RoadmapUpgradeJpaRepository upgradeRepository;
    @Autowired UserRoadmapJpaRepository enrollmentRepository;
    @Autowired UserRoadmapNodeJpaRepository stateRepository;
    @Autowired RoadmapUpgradeService upgradeService;

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
        assertThat(stateRepository.findAllByUserRoadmapId(oldEnrollmentId)).hasSize(4);
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

        saveNode(v1, s1, "old-stable-" + suffix, "stable-node", 1, false, "[]");
        saveNode(v1, s1, "old-split-" + suffix, "split-node", 2, false, "[]");
        saveNode(v1, s1, "old-contract-" + suffix, "contract-node", 3, false, "[]");
        saveNode(v1, s1, "old-removed-" + suffix, "removed-node", 4, false, "[]");
        saveNode(v2, s2, "new-stable-" + suffix, "stable-node", 1, false, "[]");
        saveNode(v2, s2, "new-split-" + suffix, "split-node", 2, false, "[]");
        saveNode(v2, s2, "new-contract-" + suffix, "contract-node", 3,
                contractChange, contractChange ? "[{\"type\":\"CODING\"}]" : "[]");
        saveNode(v2, s2, "new-added-" + suffix, "added-node", 4, false, "[]");
        prerequisiteRepository.save(new RoadmapNodePrerequisiteEntity(
                "old-edge-" + suffix, v1, "old-split-" + suffix, "old-stable-" + suffix));
        prerequisiteRepository.save(new RoadmapNodePrerequisiteEntity(
                "new-edge-" + suffix, v2, "new-split-" + suffix,
                contractChange ? "new-added-" + suffix : "new-stable-" + suffix));
        return new Fixture(code, v1, v2);
    }

    private void saveNode(
            String templateId, String stageId, String id, String code, int order,
            boolean artifactRequired, String quizBlueprint
    ) {
        nodeRepository.save(new RoadmapNodeEntity(
                id, templateId, stageId, code, order, code,
                "[]", "[]", "[]", "[]",
                "{\"required\":" + artifactRequired + "}", quizBlueprint,
                30, 15, "EASY", true));
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
}
