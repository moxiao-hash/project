package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.roadmap.application.RoadmapCatalogImporter;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapQuizGenerationJobJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

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
    @Autowired RoadmapQuizGenerationJobJpaRepository jobRepository;

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

        for (String summary : new String[]{"不足十字", "   一二三四五六七八九   ", "x".repeat(2001)}) {
            mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/check-ins", availableNodeId)
                            .header("Authorization", bearer(owner.token()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "idempotencyKey", "invalid-" + summary.length(),
                                    "summary", summary))))
                    .andExpect(status().isBadRequest());
        }
        MvcResult trimmedMaximum = mockMvc.perform(
                post("/api/roadmap-nodes/{nodeId}/check-ins", availableNodeId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "idempotencyKey", "trimmed-maximum",
                                "summary", " " + "x".repeat(2000) + " "))))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(objectMapper.readTree(trimmedMaximum.getResponse().getContentAsString())
                .get("summary").asText()).hasSize(2000);
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
        var leaseTokens = new java.util.HashSet<String>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            MvcResult claimed = mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/claim")
                            .header("X-Internal-Service-Token", "test-internal-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"workerId":"quiz-worker","leaseSeconds":60}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.attemptCount").value(attempt))
                    .andExpect(jsonPath("$.leaseToken").isNotEmpty())
                    .andExpect(jsonPath("$.checkInSummary").value(
                            "我掌握了 javac 编译与 java 运行的区别，并记录了类路径疑问。"))
                    .andReturn();
            jobId = objectMapper.readTree(claimed.getResponse().getContentAsString()).get("id").asText();
            String leaseToken = objectMapper.readTree(claimed.getResponse().getContentAsString())
                    .get("leaseToken").asText();
            assertThat(leaseTokens.add(leaseToken)).isTrue();

            mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/{jobId}/heartbeat", jobId)
                            .header("X-Internal-Service-Token", "test-internal-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"workerId":"quiz-worker","leaseToken":"stale-token","leaseSeconds":60}
                                    """))
                    .andExpect(status().isConflict());
            mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/{jobId}/heartbeat", jobId)
                            .header("X-Internal-Service-Token", "test-internal-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"workerId":"quiz-worker","leaseToken":"%s","leaseSeconds":60}
                                    """.formatted(leaseToken)))
                    .andExpect(status().isOk());
            if (attempt == 3) {
                jdbcTemplate.update("""
                        UPDATE roadmap_quiz_generation_jobs
                        SET lease_until = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                        WHERE id = ?
                        """, jobId);
                break;
            }
            mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/{jobId}/fail", jobId)
                            .header("X-Internal-Service-Token", "test-internal-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"workerId":"quiz-worker","leaseToken":"%s",
                                     "error":"Python service unavailable"}
                                    """.formatted(leaseToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"));
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
        mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/quiz-retries", availableNodeId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"retry-after-expired-lease"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
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
        String leaseToken = objectMapper.readTree(claim.getResponse().getContentAsString())
                .get("leaseToken").asText();

        java.util.List<java.util.Map<String, Object>> completionQuestions = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            java.util.Map<String, Object> question = new java.util.HashMap<>();
            question.put("type", "SINGLE_CHOICE");
            question.put("knowledgePoint", "javac 与 java " + index);
            question.put("questionText", "第 " + (index + 1) + " 个命令场景应如何判断？");
            question.put("options", java.util.List.of("javac Main.java", "java Main.java"));
            question.put("correctAnswers", java.util.List.of("javac Main.java"));
            question.put("explanation", "javac 生成字节码，java 启动 JVM。");
            question.put("points", 20);
            question.put("coverageNodeId", availableNodeId);
            question.put("practical", true);
            question.put("sources", java.util.List.of(java.util.Map.of(
                    "sourceType", "ROADMAP_CATALOG", "title", "Java 环境",
                    "locator", "roadmap-node:" + availableNodeId,
                    "snippet", "javac 与 java 命令")));
            question.put("questionSignature", "java-env-command-v" + (index + 1));
            completionQuestions.add(question);
        }
        String enrollmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_roadmaps WHERE owner_id = ? AND active_slot = 'CURRENT'",
                String.class, owner.userId());
        String stateId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_roadmap_nodes WHERE user_roadmap_id = ? AND node_id = ?",
                String.class, enrollmentId, availableNodeId);
        String templateId = jdbcTemplate.queryForObject(
                "SELECT template_id FROM user_roadmaps WHERE id = ?", String.class, enrollmentId);
        MvcResult createdQuiz = mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "ownerId", owner.userId(),
                                "roadmapNodeId", availableNodeId,
                                "userRoadmapId", enrollmentId,
                                "userRoadmapNodeId", stateId,
                                "roadmapTemplateId", templateId,
                                "purpose", "NODE",
                                "title", "Java 环境节点测验",
                                "modelName", "test-model",
                                "questions", completionQuestions))))
                .andExpect(status().isCreated())
                .andReturn();
        String quizId = objectMapper.readTree(createdQuiz.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/{jobId}/complete", jobId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"quiz-worker","leaseToken":"%s","quizId":"%s"}
                                """.formatted(leaseToken, quizId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        Instant preservedAt = Instant.parse("2025-01-02T03:04:05Z");
        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes
                SET quiz_status = 'PASSED', updated_at = ?, row_version = 17
                WHERE owner_id = ? AND node_id = ?
                """, preservedAt, owner.userId(), availableNodeId);
        mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/{jobId}/complete", jobId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"quiz-worker","leaseToken":"%s","quizId":"%s"}
                                """.formatted(leaseToken, quizId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quizId").value(quizId));
        assertThat(jdbcTemplate.queryForMap(
                "SELECT user_roadmap_id, user_roadmap_node_id, roadmap_template_id "
                        + "FROM quizzes WHERE id = ?", quizId))
                .containsEntry("USER_ROADMAP_ID", enrollmentId)
                .containsEntry("USER_ROADMAP_NODE_ID", stateId)
                .containsEntry("ROADMAP_TEMPLATE_ID", templateId);
        assertThat(jdbcTemplate.queryForList(
                "SELECT points, coverage_node_id, practical FROM quiz_questions WHERE quiz_id = ?",
                quizId)).allSatisfy(row -> {
                    assertThat(row.get("POINTS")).isEqualTo(20);
                    assertThat(row.get("COVERAGE_NODE_ID")).isEqualTo(availableNodeId);
                    assertThat(row.get("PRACTICAL")).isEqualTo(true);
                });
        var preservedState = jdbcTemplate.queryForMap("""
                SELECT quiz_status, updated_at, row_version FROM user_roadmap_nodes
                WHERE owner_id = ? AND node_id = ?
                """, owner.userId(), availableNodeId);
        assertThat(preservedState.get("QUIZ_STATUS")).isEqualTo("PASSED");
        assertThat(((java.time.OffsetDateTime) preservedState.get("UPDATED_AT")).toInstant())
                .isEqualTo(preservedAt);
        assertThat(((Number) preservedState.get("ROW_VERSION")).longValue()).isEqualTo(17L);
        String differentQuizId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO quizzes
                    (id, owner_id, roadmap_node_id, purpose, title, model_name, created_at)
                VALUES (?, ?, ?, 'NODE', '不同测验', 'test-model', CURRENT_TIMESTAMP)
                """, differentQuizId, owner.userId(), availableNodeId);
        mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/{jobId}/complete", jobId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"quiz-worker","leaseToken":"%s","quizId":"%s"}
                                """.formatted(leaseToken, differentQuizId)))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/roadmap-nodes/{nodeId}/quiz", availableNodeId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.quizId").value(quizId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT purpose FROM quizzes WHERE id = ?", String.class, quizId)).isEqualTo("NODE");
        assertThat(jdbcTemplate.queryForList(
                "SELECT question_signature FROM quiz_questions WHERE quiz_id = ?",
                String.class, quizId)).contains("java-env-command-v1").hasSize(5);
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
        String signedQuestion = question.substring(0, question.lastIndexOf('}'))
                + ",\"questionSignature\":\"origin-signature\"}";
        mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"%s","roadmapNodeId":"%s","purpose":"NODE",
                                 "title":"题量不足","modelName":"test-model","questions":[%s]}
                                """.formatted(owner.userId(), availableNodeId, signedQuestion)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"%s","roadmapNodeId":"%s",
                                 "title":"缺少 purpose","modelName":"test-model","questions":[%s]}
                                """.formatted(owner.userId(), availableNodeId, signedQuestion)))
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

    @Test
    void reservesDistinctEnrollmentAndStageOriginsForFutureQuizPurposes() throws Exception {
        Registration owner = register("future-quiz-origins");
        enrollV2(owner.token());
        String enrollmentId = jdbcTemplate.queryForObject("""
                SELECT id FROM user_roadmaps WHERE owner_id = ? AND active_slot = 'CURRENT'
                """, String.class, owner.userId());
        String stageId = jdbcTemplate.queryForObject(
                "SELECT stage_id FROM roadmap_nodes WHERE id = ?", String.class, availableNodeId);
        String enrollmentTemplateId = jdbcTemplate.queryForObject(
                "SELECT template_id FROM user_roadmaps WHERE id = ?", String.class, enrollmentId);
        String question = """
                {"type":"SINGLE_CHOICE","knowledgePoint":"路线诊断",
                 "questionText":"哪项结果表示仍需复习当前阶段？","options":["未达标","已达标"],
                 "correctAnswers":["未达标"],"explanation":"诊断用于定位薄弱点。",
                 "questionSignature":"future-origin-signature"}
                """;

        mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"%s","userRoadmapId":"%s","purpose":"DIAGNOSTIC",
                                 "title":"路线诊断","modelName":"test-model","questions":[%s]}
                                """.formatted(owner.userId(), enrollmentId, question)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"%s","userRoadmapId":"%s","roadmapStageId":"%s",
                                 "roadmapTemplateId":"%s",
                                 "purpose":"STAGE_GRADUATION","title":"阶段毕业测验",
                                 "modelName":"test-model","questions":[%s]}
                                """.formatted(owner.userId(), enrollmentId, stageId,
                                enrollmentTemplateId, question)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"%s","userRoadmapId":"%s","purpose":"STAGE_GRADUATION",
                                 "title":"缺少阶段","modelName":"test-model","questions":[%s]}
                                """.formatted(owner.userId(), enrollmentId, question)))
                .andExpect(status().isBadRequest());

        String v1TemplateId = templateRepository.findByRoadmapCodeAndTemplateVersion(
                "studypilot-java-ai", 1).orElseThrow().getId();
        String foreignTemplateStageId = jdbcTemplate.queryForObject("""
                SELECT id FROM roadmap_stages WHERE template_id = ? ORDER BY stage_order LIMIT 1
                """, String.class, v1TemplateId);
        mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"%s","userRoadmapId":"%s",
                                 "roadmapStageId":"%s","roadmapTemplateId":"%s",
                                 "purpose":"STAGE_GRADUATION","title":"跨模板阶段毕业测验",
                                 "modelName":"test-model","questions":[%s]}
                                """.formatted(owner.userId(), enrollmentId,
                                foreignTemplateStageId, v1TemplateId, question)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    void reaperQuerySelectsOnlyExpiredExhaustedLeasesAndHonorsThePageLimit() throws Exception {
        Registration first = register("expired-reaper-first");
        Registration second = register("expired-reaper-second");
        Registration active = register("active-reaper");
        for (Registration registration : java.util.List.of(first, second, active)) {
            enrollV2(registration.token());
            submitCheckIn(registration.token(), "reaper-" + registration.userId());
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE roadmap_quiz_generation_jobs
                SET status = 'LEASED', attempt_count = 3, worker_id = 'worker',
                    lease_token = RANDOM_UUID(), lease_until = ?
                WHERE owner_id IN (?, ?)
                """, now.minusSeconds(1), first.userId(), second.userId());
        jdbcTemplate.update("""
                UPDATE roadmap_quiz_generation_jobs
                SET status = 'LEASED', attempt_count = 3, worker_id = 'worker',
                    lease_token = RANDOM_UUID(), lease_until = ?
                WHERE owner_id = ?
                """, now.plusSeconds(60), active.userId());

        var expired = jobRepository.findExpiredExhaustedIds(now, PageRequest.of(0, 1));

        assertThat(expired).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_id FROM roadmap_quiz_generation_jobs WHERE id = ?",
                String.class, expired.get(0))).isIn(first.userId(), second.userId());
    }

    @Test
    void exposesOnlyCurrentNodeAndDirectPrerequisitesToTheGenerationWorker() throws Exception {
        Registration owner = register("roadmap-quiz-context");
        enrollV2(owner.token());
        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes SET availability_status = 'AVAILABLE'
                WHERE owner_id = ? AND node_id = ?
                """, owner.userId(), lockedNodeId);
        mockMvc.perform(post("/api/roadmap-nodes/{nodeId}/check-ins", lockedNodeId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"context-check-in",
                                 "summary":"我练习了变量类型转换，并记录了整数溢出的疑问。"}
                                """))
                .andExpect(status().isCreated());
        MvcResult claim = mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/claim")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"roadmap-worker","leaseSeconds":60}
                                """))
                .andExpect(status().isOk()).andReturn();
        String jobId = objectMapper.readTree(claim.getResponse().getContentAsString())
                .get("id").asText();
        String contextLeaseToken = objectMapper.readTree(claim.getResponse().getContentAsString())
                .get("leaseToken").asText();

        mockMvc.perform(get("/internal/roadmap-quiz-generation-jobs/{jobId}/context", jobId)
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/internal/roadmap-quiz-generation-jobs/{jobId}/context", jobId)
                        .param("workerId", "roadmap-worker")
                        .param("leaseToken", contextLeaseToken)
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(owner.userId()))
                .andExpect(jsonPath("$.node.id").value(lockedNodeId))
                .andExpect(jsonPath("$.node.code").value("variables-types-conversion"))
                .andExpect(jsonPath("$.node.objectives.length()").value(2))
                .andExpect(jsonPath("$.node.highFrequency.length()").value(2))
                .andExpect(jsonPath("$.node.quizBlueprint.length()").value(5))
                .andExpect(jsonPath("$.node.quizBlueprint[0].timeSensitive").value(false))
                .andExpect(jsonPath("$.directPrerequisites.length()").value(1))
                .andExpect(jsonPath("$.directPrerequisites[0].id").value(availableNodeId))
                .andExpect(jsonPath("$.recentQuestionSignatures.length()").value(0))
                .andExpect(jsonPath("$.officialDomains[0]").value("docs.oracle.com"));
    }

    @Test
    void recordsSeventyAsTheNodePassThresholdAndPreservesEarlierAttempts() throws Exception {
        Registration owner = register("roadmap-quiz-attempts");
        enrollV2(owner.token());
        submitCheckIn(owner.token(), "attempt-check-in");
        MvcResult claim = mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/claim")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"attempt-worker\",\"leaseSeconds\":60}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode claimed = objectMapper.readTree(claim.getResponse().getContentAsString());

        java.util.List<java.util.Map<String, Object>> questions = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            java.util.Map<String, Object> question = new java.util.HashMap<>();
            question.put("type", "SINGLE_CHOICE");
            question.put("knowledgePoint", "Java 环境实践 " + index);
            question.put("questionText", "第 " + (index + 1) + " 题应选择哪个编译命令？");
            question.put("options", java.util.List.of("A", "B"));
            question.put("correctAnswers", java.util.List.of("A"));
            question.put("explanation", "A 是正确命令。");
            question.put("points", 20);
            question.put("coverageNodeId", availableNodeId);
            question.put("practical", index < 3);
            question.put("sources", java.util.List.of(java.util.Map.of(
                    "sourceType", "ROADMAP_CATALOG", "title", "Java 环境",
                    "locator", "roadmap-node:" + availableNodeId,
                    "snippet", "javac 与 java 命令")));
            question.put("questionSignature", "node-attempt-signature-" + index);
            questions.add(question);
        }
        String enrollmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_roadmaps WHERE owner_id = ? AND active_slot = 'CURRENT'",
                String.class, owner.userId());
        String stateId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_roadmap_nodes WHERE user_roadmap_id = ? AND node_id = ?",
                String.class, enrollmentId, availableNodeId);
        String templateId = jdbcTemplate.queryForObject(
                "SELECT template_id FROM user_roadmaps WHERE id = ?", String.class, enrollmentId);
        MvcResult created = mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "ownerId", owner.userId(),
                                "roadmapNodeId", availableNodeId,
                                "userRoadmapId", enrollmentId,
                                "userRoadmapNodeId", stateId,
                                "roadmapTemplateId", templateId,
                                "purpose", "NODE",
                                "title", "五题节点测验",
                                "modelName", "test-model",
                                "questions", questions))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode quiz = objectMapper.readTree(created.getResponse().getContentAsString());
        String quizId = quiz.get("id").asText();
        mockMvc.perform(post("/internal/roadmap-quiz-generation-jobs/{jobId}/complete",
                        claimed.get("id").asText())
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "workerId", "attempt-worker",
                                "leaseToken", claimed.get("leaseToken").asText(),
                                "quizId", quizId))))
                .andExpect(status().isOk());

        jdbcTemplate.update("""
                UPDATE user_roadmaps SET status = 'SUPERSEDED', active_slot = NULL
                WHERE id = ?
                """, enrollmentId);
        enrollV1(owner.token());

        String failedAttemptId = submitQuizAttempt(owner.token(), quiz, 3, "score-60")
                .get("id").asText();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT quiz_status FROM user_roadmap_nodes WHERE owner_id = ? AND node_id = ?
                """, String.class, owner.userId(), availableNodeId)).isEqualTo("FAILED");

        JsonNode passed = submitQuizAttempt(owner.token(), quiz, 4, "score-80");
        assertThat(passed.get("score").asDouble()).isEqualTo(80.0);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT quiz_status FROM user_roadmap_nodes WHERE owner_id = ? AND node_id = ?
                """, String.class, owner.userId(), availableNodeId)).isEqualTo("PASSED");
        mockMvc.perform(get("/api/quiz-attempts/{attemptId}", failedAttemptId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(60.0))
                .andExpect(jsonPath("$.results.length()").value(5))
                .andExpect(jsonPath("$.results[0].explanation").value("A 是正确命令。"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT completion_status FROM user_roadmap_nodes
                WHERE owner_id = ? AND node_id = ?
                """, String.class, owner.userId(), availableNodeId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT availability_status FROM user_roadmap_nodes
                WHERE owner_id = ? AND node_id = ?
                """, String.class, owner.userId(), lockedNodeId)).isEqualTo("AVAILABLE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_roadmap_nodes state
                JOIN user_roadmaps roadmap ON roadmap.id = state.user_roadmap_id
                WHERE roadmap.owner_id = ? AND roadmap.active_slot = 'CURRENT'
                  AND state.completion_status = 'COMPLETED'
                """, Integer.class, owner.userId())).isZero();
    }

    private JsonNode submitQuizAttempt(
            String token, JsonNode quiz, int correctCount, String idempotencyKey
    ) throws Exception {
        java.util.List<java.util.Map<String, Object>> answers = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            answers.add(java.util.Map.of(
                    "questionId", quiz.get("questions").get(index).get("id").asText(),
                    "selectedAnswers", java.util.List.of(index < correctCount ? "A" : "B")));
        }
        MvcResult result = mockMvc.perform(post("/api/quizzes/{quizId}/attempts", quiz.get("id").asText())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "idempotencyKey", idempotencyKey, "answers", answers))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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

    private void enrollV1(String token) throws Exception {
        mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roadmapCode":"studypilot-java-ai","templateVersion":1}
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
