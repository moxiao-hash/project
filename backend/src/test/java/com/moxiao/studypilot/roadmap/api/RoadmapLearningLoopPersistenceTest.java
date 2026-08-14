package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.roadmap.application.RoadmapCatalogImporter;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
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
class RoadmapLearningLoopPersistenceTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CourseCatalogImporter courseCatalogImporter;
    @Autowired RoadmapCatalogImporter catalogImporter;
    @Autowired RoadmapTemplateJpaRepository templateRepository;
    @Autowired RoadmapNodeJpaRepository nodeRepository;

    private String availableNodeId;
    private String lockedNodeId;

    @BeforeEach
    void importCatalogAndResolveNodes() {
        jdbcTemplate.update("DELETE FROM roadmap_quiz_generation_jobs");
        jdbcTemplate.update("DELETE FROM roadmap_node_check_ins");
        courseCatalogImporter.importCatalog();
        catalogImporter.importCatalog();
        String templateId = templateRepository.findByRoadmapCodeAndTemplateVersion(
                "studypilot-java-ai", 2).orElseThrow().getId();
        var nodes = nodeRepository.findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(templateId);
        availableNodeId = nodes.stream()
                .filter(node -> node.getNodeCode().equals("java-environment-first-program"))
                .findFirst().orElseThrow().getId();
        lockedNodeId = nodes.stream()
                .filter(node -> node.getNodeCode().equals("variables-types-conversion"))
                .findFirst().orElseThrow().getId();
    }

    @Test
    void persistsOneIdempotentCheckInAndGenerationJobInTheSameTransaction() throws Exception {
        Registration owner = register("check-in-owner");
        enrollV2(owner.token());
        String body = """
                {"idempotencyKey":"check-in-1",
                 "summary":"我掌握了 javac 编译与 java 运行的区别，并记录了类路径疑问。"}
                """;

        MvcResult first = mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/check-ins", availableNodeId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary").value(
                        "我掌握了 javac 编译与 java 运行的区别，并记录了类路径疑问。"))
                .andExpect(jsonPath("$.quizGeneration.status").value("PENDING"))
                .andReturn();
        String checkInId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/check-ins", availableNodeId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(checkInId));
        mockMvc.perform(get("/api/roadmap-nodes/{nodeId}/check-ins", availableNodeId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(checkInId));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roadmap_node_check_ins WHERE owner_id = ?",
                Integer.class, owner.userId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roadmap_quiz_generation_jobs WHERE owner_id = ?",
                Integer.class, owner.userId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT check_in_status FROM user_roadmap_nodes
                WHERE owner_id = ? AND node_id = ?
                """, String.class, owner.userId(), availableNodeId)).isEqualTo("SUBMITTED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT quiz_status FROM user_roadmap_nodes
                WHERE owner_id = ? AND node_id = ?
                """, String.class, owner.userId(), availableNodeId)).isEqualTo("GENERATING");
    }

    @Test
    void validatesSummaryAndHidesLockedOrForeignNodes() throws Exception {
        Registration owner = register("check-in-validation");
        Registration foreign = register("check-in-foreign");
        enrollV2(owner.token());

        for (String summary : new String[]{"不足十字", "x".repeat(2001)}) {
            mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/check-ins", availableNodeId)
                            .header("Authorization", bearer(owner.token()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "idempotencyKey", "invalid-" + summary.length(),
                                    "summary", summary))))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/check-ins", lockedNodeId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"locked-node",
                                 "summary":"我总结了当前节点的收获，但前置节点尚未完成。"}
                                """))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/roadmap-nodes/{nodeId}/check-ins", availableNodeId)
                        .header("Authorization", bearer(foreign.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/check-ins", availableNodeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"anonymous",
                                 "summary":"匿名用户不应能写入任何路线节点打卡记录。"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void leasesHeartbeatsAndFailsGenerationAtThreeAttemptsWithoutLosingCheckIn() throws Exception {
        Registration owner = register("quiz-job-owner");
        enrollV2(owner.token());
        submitCheckIn(owner.token(), "durable-job");

        mockMvc.perform(get("/api/roadmap-nodes/{nodeId}/quiz", availableNodeId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.generation.status").value("PENDING"));

        String jobId = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            MvcResult claimed = mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/claim")
                            .header("X-Internal-Service-Token", "test-internal-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"workerId":"quiz-worker","leaseSeconds":60}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.attemptCount").value(attempt))
                    .andExpect(jsonPath("$.checkInSummary").value(
                            "我掌握了 javac 编译与 java 运行的区别，并记录了类路径疑问。"))
                    .andReturn();
            jobId = objectMapper.readTree(claimed.getResponse().getContentAsString()).get("id").asText();

            mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/{jobId}/heartbeat", jobId)
                            .header("X-Internal-Service-Token", "test-internal-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"workerId":"another-worker","leaseSeconds":60}
                                    """))
                    .andExpect(status().isConflict());
            mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/{jobId}/heartbeat", jobId)
                            .header("X-Internal-Service-Token", "test-internal-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"workerId":"quiz-worker","leaseSeconds":60}
                                    """))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/{jobId}/fail", jobId)
                            .header("X-Internal-Service-Token", "test-internal-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"workerId":"quiz-worker","error":"Python service unavailable"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(attempt == 3 ? "FAILED" : "PENDING"));
        }

        mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/claim")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"quiz-worker","leaseSeconds":60}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/roadmap-nodes/{nodeId}/quiz", availableNodeId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.generation.jobId").value(jobId));
        mockMvc.perform(get("/api/roadmap-nodes/{nodeId}/check-ins", availableNodeId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void retriesFailedGenerationIdempotentlyAndLimitsRetriesToThree() throws Exception {
        Registration owner = register("quiz-retry-owner");
        enrollV2(owner.token());
        submitCheckIn(owner.token(), "retry-job");
        jdbcTemplate.update("""
                UPDATE roadmap_quiz_generation_jobs
                SET status = 'FAILED', attempt_count = 3, last_error = 'generation failed'
                WHERE owner_id = ?
                """, owner.userId());
        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes SET quiz_status = 'FAILED'
                WHERE owner_id = ? AND node_id = ?
                """, owner.userId(), availableNodeId);

        String retryBody = """
                {"idempotencyKey":"retry-1"}
                """;
        MvcResult first = mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/quiz-retries", availableNodeId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.retrySequence").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String retryId = objectMapper.readTree(first.getResponse().getContentAsString()).get("jobId").asText();
        mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/quiz-retries", availableNodeId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").value(retryId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roadmap_quiz_generation_jobs WHERE owner_id = ?",
                Integer.class, owner.userId())).isEqualTo(2);
    }

    @Test
    void bindsNodeQuizQuestionsBySignatureAndCompletesTheLeasedJob() throws Exception {
        Registration owner = register("quiz-complete-owner");
        enrollV2(owner.token());
        submitCheckIn(owner.token(), "complete-job");
        MvcResult claim = mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/claim")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"quiz-worker","leaseSeconds":60}
                                """))
                .andExpect(status().isOk()).andReturn();
        String jobId = objectMapper.readTree(claim.getResponse().getContentAsString()).get("id").asText();

        MvcResult createdQuiz = mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"%s","roadmapNodeId":"%s","purpose":"NODE",
                                 "title":"Java 环境节点测验","modelName":"test-model","questions":[
                                  {"type":"SINGLE_CHOICE","knowledgePoint":"javac 与 java",
                                   "questionText":"哪个命令只负责编译 Main.java？",
                                   "options":["javac Main.java","java Main.java"],
                                   "correctAnswers":["javac Main.java"],
                                   "explanation":"javac 生成字节码，java 启动 JVM。",
                                   "questionSignature":"java-env-command-v1"}]}
                                """.formatted(owner.userId(), availableNodeId)))
                .andExpect(status().isCreated())
                .andReturn();
        String quizId = objectMapper.readTree(createdQuiz.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/{jobId}/complete", jobId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"quiz-worker","quizId":"%s"}
                                """.formatted(quizId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(get("/api/roadmap-nodes/{nodeId}/quiz", availableNodeId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.quizId").value(quizId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT purpose FROM quizzes WHERE id = ?", String.class, quizId)).isEqualTo("NODE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT question_signature FROM quiz_questions WHERE quiz_id = ?",
                String.class, quizId)).isEqualTo("java-env-command-v1");
    }

    @Test
    void rejectsRoadmapQuizzesWithoutAUniqueQuestionSignatureOrWithMixedOrigins() throws Exception {
        Registration owner = register("quiz-origin-owner");
        enrollV2(owner.token());
        String question = """
                {"type":"SINGLE_CHOICE","knowledgePoint":"javac 与 java",
                 "questionText":"哪个命令只负责编译 Main.java？",
                 "options":["javac Main.java","java Main.java"],
                 "correctAnswers":["javac Main.java"],
                 "explanation":"javac 生成字节码，java 启动 JVM。"}
                """;
        mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"%s","roadmapNodeId":"%s","purpose":"NODE",
                                 "title":"缺少签名","modelName":"test-model","questions":[%s]}
                                """.formatted(owner.userId(), availableNodeId, question)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"%s","taskId":"task-1","roadmapNodeId":"%s","purpose":"NODE",
                                 "title":"混合来源","modelName":"test-model","questions":[%s]}
                                """.formatted(owner.userId(), availableNodeId, question)))
                .andExpect(status().isBadRequest());
    }

    private Registration register(String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s-%d@example.com","password":"Password123!",
                                 "displayName":"路线学习用户"}
                                """.formatted(label, System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode registration = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(
                registration.get("accessToken").asText(), registration.get("user").get("id").asText());
    }

    private void enrollV2(String token) throws Exception {
        mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roadmapCode":"studypilot-java-ai","templateVersion":2}
                                """))
                .andExpect(status().isCreated());
    }

    private void submitCheckIn(String token, String idempotencyKey) throws Exception {
        mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/check-ins", availableNodeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"%s",
                                 "summary":"我掌握了 javac 编译与 java 运行的区别，并记录了类路径疑问。"}
                                """.formatted(idempotencyKey)))
                .andExpect(status().isCreated());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record Registration(String token, String userId) { }
}
