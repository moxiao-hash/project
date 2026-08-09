package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.application.RoadmapCatalogImporter;
import com.moxiao.studypilot.roadmap.application.RoadmapQueryService;
import com.moxiao.studypilot.roadmap.domain.AvailabilityStatus;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import com.moxiao.studypilot.roadmap.domain.RoadmapPublicationStatus;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "studypilot.roadmap.catalog-import-enabled=false")
@AutoConfigureMockMvc
class RoadmapWorkflowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RoadmapCatalogImporter importer;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RoadmapTemplateJpaRepository templateRepository;
    @Autowired RoadmapStageJpaRepository stageRepository;
    @Autowired RoadmapNodeJpaRepository nodeRepository;

    @BeforeEach
    void importCatalogAndInstallProductionEnrollmentConstraints() {
        jdbcTemplate.execute("""
                ALTER TABLE user_roadmaps ADD CONSTRAINT IF NOT EXISTS uk_user_roadmap_template
                UNIQUE (owner_id, template_id)
                """);
        jdbcTemplate.execute("""
                ALTER TABLE user_roadmaps ADD CONSTRAINT IF NOT EXISTS uk_user_roadmap_active_slot
                UNIQUE (owner_id, active_slot)
                """);
        importer.importCatalog();
    }

    @Test
    void returnsTheAuthenticatedUsersOrderedCurrentRoadmapMap() throws Exception {
        String token = register("map");
        JsonNode enrollment = enroll(token);

        mockMvc.perform(get("/api/roadmaps/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(enrollment.get("id").asText()))
                .andExpect(jsonPath("$.roadmapCode").value("studypilot-java-ai"))
                .andExpect(jsonPath("$.templateVersion").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        MvcResult mapResult = mockMvc.perform(get("/api/roadmaps/current/map")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentId").value(enrollment.get("id").asText()))
                .andExpect(jsonPath("$.roadmapCode").value("studypilot-java-ai"))
                .andExpect(jsonPath("$.templateVersion").value(1))
                .andExpect(jsonPath("$.completedRequiredNodes").value(0))
                .andExpect(jsonPath("$.totalRequiredNodes").value(62))
                .andExpect(jsonPath("$.stages.length()").value(12))
                .andExpect(jsonPath("$.stages[0].code").value("java-core"))
                .andExpect(jsonPath("$.stages[0].order").value(1))
                .andExpect(jsonPath("$.stages[0].completedRequiredNodes").value(0))
                .andExpect(jsonPath("$.stages[0].totalRequiredNodes").value(5))
                .andExpect(jsonPath("$.stages[0].nodes[0].code").value("java-syntax-oop"))
                .andExpect(jsonPath("$.stages[0].nodes[0].order").value(1))
                .andExpect(jsonPath("$.stages[0].nodes[0].availabilityStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.stages[0].nodes[0].learningStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.stages[0].nodes[0].checkInStatus").value("MISSING"))
                .andExpect(jsonPath("$.stages[0].nodes[0].quizStatus").value("NOT_GENERATED"))
                .andExpect(jsonPath("$.stages[0].nodes[0].completionStatus").value("INCOMPLETE"))
                .andExpect(jsonPath("$.stages[0].nodes[0].displayStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.stages[1].nodes[0].prerequisiteCodes[0]")
                        .value("java-maven-testing"))
                .andReturn();

        JsonNode map = objectMapper.readTree(mapResult.getResponse().getContentAsString());
        String stageId = map.get("stages").get(0).get("id").asText();
        String nodeId = map.get("stages").get(0).get("nodes").get(0).get("id").asText();

        mockMvc.perform(get("/api/roadmaps/current/stages/{stageId}", stageId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(stageId))
                .andExpect(jsonPath("$.code").value("java-core"))
                .andExpect(jsonPath("$.nodes.length()").value(5));

        mockMvc.perform(get("/api/roadmaps/current/nodes/{nodeId}", nodeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(nodeId))
                .andExpect(jsonPath("$.code").value("java-syntax-oop"))
                .andExpect(jsonPath("$.objectives.length()").value(2))
                .andExpect(jsonPath("$.highFrequency.length()").value(2))
                .andExpect(jsonPath("$.commonMistakes.length()").value(2))
                .andExpect(jsonPath("$.searchKeywords.length()").value(4));
    }

    @Test
    void isolatesCurrentRoadmapsAndRequiresAuthentication() throws Exception {
        String enrolledToken = register("owner");
        enroll(enrolledToken);
        String otherToken = register("other");

        mockMvc.perform(get("/api/roadmaps/current/map")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/roadmaps/current")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/roadmaps/current/map"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsStageAndNodeIdsOutsideTheCurrentTemplate() throws Exception {
        String token = register("foreign");
        enroll(token);
        String suffix = UUID.randomUUID().toString();
        String templateId = "foreign-template-" + suffix.substring(0, 10);
        String stageId = "foreign-stage-" + suffix;
        String nodeId = "foreign-node-" + suffix;
        templateRepository.save(new RoadmapTemplateEntity(
                templateId, "foreign-" + suffix, 1, "其他路线", "其他路线",
                RoadmapPublicationStatus.PUBLISHED, "f".repeat(64), Instant.now()));
        stageRepository.save(new RoadmapStageEntity(
                stageId, templateId, "foreign-stage", 1, "其他阶段", "其他阶段", "其他项目"));
        nodeRepository.save(new RoadmapNodeEntity(
                nodeId, templateId, stageId, "foreign-node", 1, "其他节点",
                "[]", "[]", "[]", "[]", "{\"required\":false}", "[]",
                30, 15, "EASY", false));

        mockMvc.perform(get("/api/roadmaps/current/stages/{stageId}", stageId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/roadmaps/current/nodes/{nodeId}", nodeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void derivesDisplayStatusWithTheRequiredPrecedence() throws Exception {
        String token = register("status");
        JsonNode enrollment = enroll(token);
        String enrollmentId = enrollment.get("id").asText();
        String nodeId = nodeRepository.findAll().stream()
                .filter(node -> node.getNodeCode().equals("java-syntax-oop"))
                .findFirst().orElseThrow().getId();

        updateState(enrollmentId, nodeId, "LOCKED", "IN_PROGRESS", "SUBMITTED", "FAILED", "COMPLETED");
        assertDisplayStatus(token, nodeId, "COMPLETED");

        updateState(enrollmentId, nodeId, "LOCKED", "IN_PROGRESS", "SUBMITTED", "FAILED", "INCOMPLETE");
        assertDisplayStatus(token, nodeId, "LOCKED");

        updateState(enrollmentId, nodeId, "AVAILABLE", "IN_PROGRESS", "SUBMITTED", "FAILED", "INCOMPLETE");
        assertDisplayStatus(token, nodeId, "REVIEW_REQUIRED");

        updateState(enrollmentId, nodeId, "AVAILABLE", "IN_PROGRESS", "SUBMITTED", "EVALUATING", "INCOMPLETE");
        assertDisplayStatus(token, nodeId, "QUIZ_PENDING");

        updateState(enrollmentId, nodeId, "AVAILABLE", "IN_PROGRESS", "MISSING", "NOT_GENERATED", "INCOMPLETE");
        assertDisplayStatus(token, nodeId, "IN_PROGRESS");

        updateState(enrollmentId, nodeId, "AVAILABLE", "SCHEDULED", "MISSING", "NOT_GENERATED", "INCOMPLETE");
        assertDisplayStatus(token, nodeId, "SCHEDULED");

        updateState(enrollmentId, nodeId, "AVAILABLE", "NOT_STARTED", "MISSING", "NOT_GENERATED", "INCOMPLETE");
        assertDisplayStatus(token, nodeId, "AVAILABLE");
    }

    @Test
    void loadsTheMapWithOneBoundedCallPerRepository() {
        UserRoadmapJpaRepository userRoadmaps = mock(UserRoadmapJpaRepository.class);
        RoadmapTemplateJpaRepository templates = mock(RoadmapTemplateJpaRepository.class);
        RoadmapStageJpaRepository stages = mock(RoadmapStageJpaRepository.class);
        RoadmapNodeJpaRepository nodes = mock(RoadmapNodeJpaRepository.class);
        RoadmapNodePrerequisiteJpaRepository prerequisites =
                mock(RoadmapNodePrerequisiteJpaRepository.class);
        UserRoadmapNodeJpaRepository states = mock(UserRoadmapNodeJpaRepository.class);
        Instant now = Instant.now();
        UserRoadmapEntity enrollment = new UserRoadmapEntity("enrollment", "owner", "template", now);
        RoadmapTemplateEntity template = new RoadmapTemplateEntity(
                "template", "roadmap", 1, "路线", "描述",
                RoadmapPublicationStatus.PUBLISHED, "a".repeat(64), now);
        RoadmapStageEntity stage = new RoadmapStageEntity(
                "stage", "template", "stage-code", 1, "阶段", "描述", "项目");
        RoadmapNodeEntity node = new RoadmapNodeEntity(
                "node", "template", "stage", "node-code", 1, "节点",
                "[]", "[]", "[]", "[]", "{\"required\":false}", "[]",
                30, 15, "EASY", true);
        UserRoadmapNodeEntity state = new UserRoadmapNodeEntity(
                "state", "enrollment", "node", "owner", "template",
                AvailabilityStatus.AVAILABLE, false, now);
        when(userRoadmaps.findByOwnerIdAndActiveSlot("owner", "CURRENT"))
                .thenReturn(Optional.of(enrollment));
        when(templates.findById("template")).thenReturn(Optional.of(template));
        when(stages.findAllByTemplateIdOrderByStageOrderAsc("template"))
                .thenReturn(List.of(stage));
        when(nodes.findAllByTemplateIdOrderByStageIdAscNodeOrderAsc("template"))
                .thenReturn(List.of(node));
        when(prerequisites.findAllByTemplateId("template")).thenReturn(List.of());
        when(states.findAllByUserRoadmapId("enrollment")).thenReturn(List.of(state));
        RoadmapQueryService service = new RoadmapQueryService(
                userRoadmaps, templates, stages, nodes, prerequisites, states, objectMapper);

        service.currentMap("owner");

        verify(userRoadmaps).findByOwnerIdAndActiveSlot("owner", "CURRENT");
        verify(templates).findById("template");
        verify(stages).findAllByTemplateIdOrderByStageOrderAsc("template");
        verify(nodes).findAllByTemplateIdOrderByStageIdAscNodeOrderAsc("template");
        verify(prerequisites).findAllByTemplateId("template");
        verify(states).findAllByUserRoadmapId("enrollment");
        verifyNoMoreInteractions(userRoadmaps, templates, stages, nodes, prerequisites, states);
    }

    @Test
    void nodeDetailIgnoresMissingStateForAnUnrelatedNode() throws Exception {
        String token = register("node-isolation");
        JsonNode enrollment = enroll(token);
        RoadmapNodeEntity requested = nodeRepository.findAll().stream()
                .filter(node -> node.getNodeCode().equals("java-syntax-oop"))
                .findFirst().orElseThrow();
        RoadmapNodeEntity unrelated = nodeRepository.findAll().stream()
                .filter(node -> node.getNodeCode().equals("release-e2e"))
                .findFirst().orElseThrow();
        jdbcTemplate.update("""
                DELETE FROM user_roadmap_nodes
                WHERE user_roadmap_id = ? AND node_id = ?
                """, enrollment.get("id").asText(), unrelated.getId());

        mockMvc.perform(get("/api/roadmaps/current/nodes/{nodeId}", requested.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("java-syntax-oop"));
    }

    @Test
    void stageDetailIgnoresMalformedMetadataInAnotherStage() throws Exception {
        String token = register("stage-isolation");
        enroll(token);
        RoadmapStageEntity requested = stageRepository.findAll().stream()
                .filter(stage -> stage.getStageCode().equals("java-core"))
                .findFirst().orElseThrow();
        RoadmapNodeEntity unrelated = nodeRepository.findAll().stream()
                .filter(node -> node.getNodeCode().equals("release-e2e"))
                .findFirst().orElseThrow();
        try {
            jdbcTemplate.update(
                    "UPDATE roadmap_nodes SET objectives_json = ? WHERE id = ?",
                    "{not-json", unrelated.getId());

            mockMvc.perform(get("/api/roadmaps/current/stages/{stageId}", requested.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("java-core"))
                    .andExpect(jsonPath("$.nodes.length()").value(5));
        } finally {
            jdbcTemplate.update(
                    "UPDATE roadmap_nodes SET objectives_json = ? WHERE id = ?",
                    unrelated.getObjectivesJson(), unrelated.getId());
        }
    }

    @Test
    void preservesCatalogPrerequisiteOrderAcrossMapStageAndNodeDetails() throws Exception {
        String token = register("prerequisite-order");
        enroll(token);

        MvcResult mapResult = mockMvc.perform(get("/api/roadmaps/current/map")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[2].nodes[5].code")
                        .value("data-access-comparison"))
                .andExpect(jsonPath("$.stages[2].nodes[5].prerequisiteCodes[0]")
                        .value("mybatis-plus"))
                .andExpect(jsonPath("$.stages[2].nodes[5].prerequisiteCodes[1]")
                        .value("jpa-core"))
                .andReturn();
        JsonNode map = objectMapper.readTree(mapResult.getResponse().getContentAsString());
        String stageId = map.get("stages").get(2).get("id").asText();
        String nodeId = map.get("stages").get(2).get("nodes").get(5).get("id").asText();

        mockMvc.perform(get("/api/roadmaps/current/stages/{stageId}", stageId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[5].prerequisiteCodes[0]")
                        .value("mybatis-plus"))
                .andExpect(jsonPath("$.nodes[5].prerequisiteCodes[1]")
                        .value("jpa-core"));
        mockMvc.perform(get("/api/roadmaps/current/nodes/{nodeId}", nodeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prerequisiteCodes[0]").value("mybatis-plus"))
                .andExpect(jsonPath("$.prerequisiteCodes[1]").value("jpa-core"));
    }

    private void updateState(
            String enrollmentId,
            String nodeId,
            String availability,
            String learning,
            String checkIn,
            String quiz,
            String completion
    ) {
        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes
                SET availability_status = ?, learning_status = ?, check_in_status = ?,
                    quiz_status = ?, completion_status = ?
                WHERE user_roadmap_id = ? AND node_id = ?
                """, availability, learning, checkIn, quiz, completion, enrollmentId, nodeId);
    }

    private void assertDisplayStatus(String token, String nodeId, String expected) throws Exception {
        mockMvc.perform(get("/api/roadmaps/current/nodes/{nodeId}", nodeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayStatus").value(expected));
    }

    private String register(String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "roadmap-%s-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "路线用户"
                                }
                                """.formatted(label, System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private JsonNode enroll(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roadmapCode": "studypilot-java-ai",
                                  "templateVersion": 1
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
