package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.roadmap.application.RoadmapCatalogImporter;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "studypilot.roadmap.catalog-import-enabled=false")
@AutoConfigureMockMvc
class RoadmapDiagnosticWorkflowTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CourseCatalogImporter courseCatalogImporter;
    @Autowired RoadmapCatalogImporter roadmapCatalogImporter;

    @BeforeEach
    void importCatalog() {
        courseCatalogImporter.importCatalog();
        roadmapCatalogImporter.importCatalog();
    }

    @Test
    void createsAndReplaysAnImmutableDiagnosticSnapshot() throws Exception {
        Registration owner = register("diagnostic-owner");
        enrollV2(owner.token());

        MvcResult created = mockMvc.perform(post("/api/roadmaps/current/diagnostic")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"diagnostic-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.questionTarget").value(10))
                .andExpect(jsonPath("$.nodeSnapshot.length()").value(10))
                .andReturn();
        JsonNode first = objectMapper.readTree(created.getResponse().getContentAsString());
        String diagnosticId = first.get("id").asText();
        String snapshot = first.get("nodeSnapshot").toString();

        jdbcTemplate.update("UPDATE user_roadmap_nodes SET learning_status = 'IN_PROGRESS' "
                + "WHERE owner_id = ?", owner.userId());

        mockMvc.perform(get("/api/roadmaps/current/diagnostic")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(diagnosticId))
                .andExpect(result -> assertThat(objectMapper.readTree(
                        result.getResponse().getContentAsString()).get("nodeSnapshot").toString())
                        .isEqualTo(snapshot));
        mockMvc.perform(post("/api/roadmaps/current/diagnostic")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"diagnostic-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(diagnosticId));
    }

    @Test
    void diagnosticIsAuthenticatedAndOwnerIsolated() throws Exception {
        Registration owner = register("diagnostic-private");
        Registration foreign = register("diagnostic-foreign");
        enrollV2(owner.token());
        enrollV2(foreign.token());
        mockMvc.perform(post("/api/roadmaps/current/diagnostic")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"private-diagnostic\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/roadmaps/current/diagnostic")
                        .header("Authorization", bearer(foreign.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/roadmaps/current/diagnostic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"anonymous\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void completedDiagnosticMarksOnlySnapshotNodesForFreshQuickVerification() throws Exception {
        Registration owner = register("diagnostic-result");
        enrollV2(owner.token());
        MvcResult created = mockMvc.perform(post("/api/roadmaps/current/diagnostic")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"diagnostic-result-1\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode diagnostic = objectMapper.readTree(created.getResponse().getContentAsString());
        String enrollmentId = diagnostic.get("userRoadmapId").asText();
        var questions = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (int index = 0; index < diagnostic.get("nodeSnapshot").size(); index++) {
            String nodeId = diagnostic.get("nodeSnapshot").get(index).get("nodeId").asText();
            questions.add(java.util.Map.of(
                    "type", "SINGLE_CHOICE", "knowledgePoint", "诊断知识点 " + index,
                    "questionText", "诊断题 " + index, "options", java.util.List.of("A", "B"),
                    "correctAnswers", java.util.List.of("A"), "explanation", "A 正确。",
                    "coverageNodeId", nodeId,
                    "questionSignature", "diagnostic-result-signature-" + index));
        }
        MvcResult claimed = mockMvc.perform(post("/internal/roadmap-diagnostic-jobs/claim")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"diagnostic-worker\",\"leaseSeconds\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeSnapshot.length()").value(10))
                .andReturn();
        JsonNode claim = objectMapper.readTree(claimed.getResponse().getContentAsString());
        MvcResult quiz = mockMvc.perform(post(
                        "/internal/roadmap-diagnostic-jobs/{id}/complete", claim.get("id").asText())
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "workerId", "diagnostic-worker",
                                "leaseToken", claim.get("leaseToken").asText(),
                                "quiz", java.util.Map.of("title", "路线诊断",
                                        "modelName", "test-model", "questions", questions)))))
                .andExpect(status().isOk()).andReturn();
        String quizId = objectMapper.readTree(quiz.getResponse().getContentAsString())
                .get("quizId").asText();
        var answers = jdbcTemplate.queryForList(
                        "SELECT id FROM quiz_questions WHERE quiz_id = ? ORDER BY question_position",
                        String.class, quizId).stream()
                .map(id -> java.util.Map.of("questionId", id,
                        "selectedAnswers", java.util.List.of("A"))).toList();
        mockMvc.perform(post("/api/quizzes/{quizId}/attempts", quizId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "idempotencyKey", "diagnostic-answer-1", "answers", answers))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.score").value(100.0));

        mockMvc.perform(get("/api/roadmaps/current/diagnostic")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.masteredNodeIds.length()").value(9));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_roadmap_nodes
                WHERE owner_id = ? AND diagnostic_mastered = TRUE
                """, Integer.class, owner.userId())).isEqualTo(9);
    }

    @Test
    void stageGraduationRequiresCompletedRequiredNodesAndAcceptedMilestones() throws Exception {
        Registration owner = register("graduation-gates");
        enrollV2(owner.token());
        String enrollmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_roadmaps WHERE owner_id = ? AND active_slot = 'CURRENT'",
                String.class, owner.userId());
        String templateId = jdbcTemplate.queryForObject(
                "SELECT template_id FROM user_roadmaps WHERE id = ?", String.class, enrollmentId);
        String stageId = jdbcTemplate.queryForObject(
                "SELECT id FROM roadmap_stages WHERE template_id = ? ORDER BY stage_order LIMIT 1",
                String.class, templateId);

        mockMvc.perform(post("/api/roadmaps/current/stages/{stageId}/graduation", stageId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"graduation-1\"}"))
                .andExpect(status().isConflict());

        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes
                SET learning_status = 'IN_PROGRESS', check_in_status = 'SUBMITTED',
                    quiz_status = 'PASSED', completion_status = 'COMPLETED',
                    artifact_status = CASE WHEN artifact_status = 'MISSING'
                        THEN 'ACCEPTED' ELSE artifact_status END,
                    completed_at = CURRENT_TIMESTAMP
                WHERE owner_id = ? AND node_id IN
                    (SELECT id FROM roadmap_nodes WHERE template_id = ? AND stage_id = ?
                     AND required_node = TRUE)
                """, owner.userId(), templateId, stageId);

        MvcResult created = mockMvc.perform(
                        post("/api/roadmaps/current/stages/{stageId}/graduation", stageId)
                                .header("Authorization", bearer(owner.token()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"idempotencyKey\":\"graduation-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.questionTarget").value(10))
                .andReturn();
        JsonNode snapshot = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("nodeSnapshot");
        assertThat(snapshot).isNotNull();

        String nineQuestions = graduationQuestions(9);
        mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graduationQuizBody(
                                owner.userId(), enrollmentId, templateId, stageId, nineQuestions)))
                .andExpect(status().isBadRequest());
        MvcResult graduationClaim = mockMvc.perform(post("/internal/roadmap-graduation-jobs/claim")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"graduation-worker\",\"leaseSeconds\":60}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode graduationJob = objectMapper.readTree(
                graduationClaim.getResponse().getContentAsString());
        MvcResult quizResult = mockMvc.perform(post(
                        "/internal/roadmap-graduation-jobs/{id}/complete",
                        graduationJob.get("id").asText())
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "workerId", "graduation-worker",
                                "leaseToken", graduationJob.get("leaseToken").asText(),
                                "quiz", java.util.Map.of("title", "阶段毕业测验",
                                        "modelName", "test-model",
                                        "questions", groundedGraduationQuestions(
                                                graduationJob.get("nodeSnapshot")))))))
                .andExpect(status().isOk()).andReturn();
        String quizId = objectMapper.readTree(quizResult.getResponse().getContentAsString())
                .get("quizId").asText();
        java.util.List<String> questionIds = jdbcTemplate.queryForList(
                "SELECT id FROM quiz_questions WHERE quiz_id = ? ORDER BY question_position",
                String.class, quizId);
        var answers = questionIds.stream().map(questionId -> java.util.Map.of(
                "questionId", questionId, "selectedAnswers", java.util.List.of("A"))).toList();
        mockMvc.perform(post("/api/quizzes/{quizId}/attempts", quizId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "idempotencyKey", "graduation-attempt-1", "answers", answers))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.score").value(100.0));

        mockMvc.perform(get("/api/roadmaps/current/stages/{stageId}/graduation", stageId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.quizId").value(quizId))
                .andExpect(result -> assertThat(objectMapper.readTree(
                                result.getResponse().getContentAsString()).get("nodeSnapshot"))
                        .isEqualTo(snapshot));
    }

    @Test
    void diagnosticMasteryQueuesFreshNodeQuizWithoutCreatingACheckIn() throws Exception {
        Registration owner = register("quick-verification");
        enrollV2(owner.token());
        String enrollmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_roadmaps WHERE owner_id = ? AND active_slot = 'CURRENT'",
                String.class, owner.userId());
        String ordinaryNodeId = jdbcTemplate.queryForObject("""
                SELECT n.id FROM roadmap_nodes n
                JOIN user_roadmaps r ON r.template_id = n.template_id
                WHERE r.id = ? AND n.artifact_requirement_json LIKE '%\"required\":false%'
                ORDER BY n.node_order LIMIT 1
                """, String.class, enrollmentId);
        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes SET diagnostic_mastered = TRUE
                WHERE user_roadmap_id = ? AND node_id = ?
                """, enrollmentId, ordinaryNodeId);

        mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/quick-verification", ordinaryNodeId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"quick-verify-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM roadmap_node_check_ins
                WHERE owner_id = ? AND node_id = ?
                """, Integer.class, owner.userId(), ordinaryNodeId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT check_in_id IS NULL FROM roadmap_quiz_generation_jobs
                WHERE owner_id = ? AND node_id = ?
                """, Boolean.class, owner.userId(), ordinaryNodeId)).isTrue();

        String milestoneNodeId = jdbcTemplate.queryForObject("""
                SELECT n.id FROM roadmap_nodes n
                JOIN user_roadmaps r ON r.template_id = n.template_id
                WHERE r.id = ? AND n.artifact_requirement_json LIKE '%\"required\":true%'
                ORDER BY n.node_order LIMIT 1
                """, String.class, enrollmentId);
        jdbcTemplate.update("UPDATE user_roadmap_nodes SET diagnostic_mastered = TRUE "
                + "WHERE user_roadmap_id = ? AND node_id = ?", enrollmentId, milestoneNodeId);
        mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/quick-verification", milestoneNodeId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"milestone-cannot-skip\"}"))
                .andExpect(status().isConflict());
    }

    private String graduationQuestions(int count) {
        var questions = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (int index = 0; index < count; index++) {
            questions.add(java.util.Map.of(
                    "type", "SINGLE_CHOICE",
                    "knowledgePoint", "阶段综合知识点 " + index,
                    "questionText", "第 " + index + " 题的正确选项是什么？",
                    "options", java.util.List.of("A", "B"),
                    "correctAnswers", java.util.List.of("A"),
                    "explanation", "A 是本题的正确答案。",
                    "questionSignature", "graduation-signature-" + count + "-" + index));
        }
        return objectMapper.writeValueAsString(questions);
    }

    private java.util.List<java.util.Map<String, Object>> groundedGraduationQuestions(
            JsonNode nodes
    ) {
        var questions = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (int index = 0; index < nodes.size(); index++) {
            String nodeId = nodes.get(index).get("nodeId").asText();
            questions.add(java.util.Map.ofEntries(
                    java.util.Map.entry("type", "SINGLE_CHOICE"),
                    java.util.Map.entry("knowledgePoint", "阶段知识点 " + index),
                    java.util.Map.entry("questionText", "阶段题 " + index),
                    java.util.Map.entry("options", java.util.List.of("A", "B")),
                    java.util.Map.entry("correctAnswers", java.util.List.of("A")),
                    java.util.Map.entry("explanation", "A 正确。"),
                    java.util.Map.entry("coverageNodeId", nodeId),
                    java.util.Map.entry("points", 10),
                    java.util.Map.entry("practical", false),
                    java.util.Map.entry("questionSignature", "graduation-grounded-" + index),
                    java.util.Map.entry("sources", java.util.List.of(java.util.Map.of(
                            "sourceType", "ROADMAP_CATALOG", "title", "路线目录",
                            "locator", "roadmap-node:" + nodeId, "snippet", "目录快照")))));
        }
        return questions;
    }

    private String graduationQuizBody(
            String ownerId, String enrollmentId, String templateId, String stageId,
            String questionsJson
    ) {
        return """
                {"ownerId":"%s","userRoadmapId":"%s","roadmapStageId":"%s",
                 "roadmapTemplateId":"%s","purpose":"STAGE_GRADUATION",
                 "title":"阶段毕业测验","modelName":"test-model","questions":%s}
                """.formatted(ownerId, enrollmentId, stageId, templateId, questionsJson);
    }

    private void enrollV2(String token) throws Exception {
        mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roadmapCode\":\"studypilot-java-ai\",\"templateVersion\":2}"))
                .andExpect(status().isCreated());
    }

    private Registration register(String prefix) throws Exception {
        String email = prefix + "-" + System.nanoTime() + "@example.com";
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", email,
                                "password", "Password123!",
                                "displayName", "路线诊断用户"))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(body.get("user").get("id").asText(),
                body.get("accessToken").asText());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Registration(String userId, String token) { }
}
