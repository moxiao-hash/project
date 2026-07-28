package com.moxiao.studypilot.assessment.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class QuizWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void generatedQuizCanBeAnsweredAndUpdatesMastery() throws Exception {
        Registration registration = registerUser();

        MvcResult quizResult = mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "title": "Spring 基础小测",
                                  "modelName": "test-model",
                                  "questions": [
                                    {
                                      "type": "SINGLE_CHOICE",
                                      "knowledgePoint": "依赖注入",
                                      "questionText": "Spring 中用于注入依赖的核心机制是？",
                                      "options": ["IoC 容器", "JVM GC", "JDBC 驱动"],
                                      "correctAnswers": ["IoC 容器"],
                                      "explanation": "IoC 容器负责对象创建和依赖装配。"
                                    }
                                  ]
                                }
                                """.formatted(registration.userId())))
                .andExpect(status().isCreated())
                .andReturn();
        String quizId = readId(quizResult);
        JsonNode created = objectMapper.readTree(quizResult.getResponse().getContentAsString());
        String questionId = created.get("questions").get(0).get("id").asText();

        mockMvc.perform(get("/api/quizzes/{id}", quizId)
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].correctAnswers").doesNotExist());

        MvcResult attemptResult = mockMvc.perform(post("/api/quizzes/{id}/attempts", quizId)
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": [
                                    {
                                      "questionId": "%s",
                                      "selectedAnswers": ["IoC 容器"]
                                    }
                                  ]
                                }
                                """.formatted(questionId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.score").value(100.0))
                .andExpect(jsonPath("$.results[0].correct").value(true))
                .andExpect(jsonPath("$.results[0].explanation").isNotEmpty())
                .andReturn();
        String attemptId = readId(attemptResult);

        mockMvc.perform(post("/api/quiz-attempts/{id}/self-assessments", attemptId)
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ratings": [{
                                    "knowledgePoint": "依赖注入",
                                    "score": 65
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].selfAssessmentScore").value(65.0));

        mockMvc.perform(get("/api/mastery")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].knowledgePoint").value("依赖注入"))
                .andExpect(jsonPath("$[0].score").value(
                        org.hamcrest.Matchers.closeTo(97.941176, 0.000001)))
                .andExpect(jsonPath("$[0].quizScore").value(100.0))
                .andExpect(jsonPath("$[0].selfAssessmentScore").value(65.0))
                .andExpect(jsonPath("$[0].evidenceCount").value(2));
    }

    @Test
    void codingQuizPersistsDifficultyTaskAndTraceableSources() throws Exception {
        Registration registration = registerUser();

        MvcResult result = mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "taskId": "task-source-1",
                                  "title": "Java 方法练习",
                                  "modelName": "deepseek-v4-pro",
                                  "questions": [
                                    {
                                      "type": "CODING",
                                      "difficulty": "EASY",
                                      "codingKind": "CODE_COMPLETION",
                                      "language": "JAVA",
                                      "knowledgePoint": "Java 方法",
                                      "questionText": "补全 add 方法",
                                      "options": [],
                                      "starterCode": "int add(int a, int b) { }",
                                      "correctAnswers": [],
                                      "explanation": "返回两个参数的和。",
                                      "rubric": {
                                        "correctness": 40,
                                        "completeness": 25,
                                        "edgeCases": 20,
                                        "clarityEfficiency": 15
                                      },
                                      "referenceAnswer": "int add(int a, int b) { return a + b; }",
                                      "sources": [
                                        {
                                          "sourceType": "MODEL_KNOWLEDGE",
                                          "title": "Java 稳定基础知识",
                                          "locator": "模型常识",
                                          "snippet": "Java 方法可以返回计算结果。"
                                        }
                                      ]
                                    }
                                  ]
                                }
                """.formatted(registration.userId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.questions[0].correctAnswers").isEmpty())
                .andReturn();

        String quizId = readId(result);
        mockMvc.perform(get("/api/quizzes/{id}", quizId)
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-source-1"))
                .andExpect(jsonPath("$.questions[0].type").value("CODING"))
                .andExpect(jsonPath("$.questions[0].difficulty").value("EASY"))
                .andExpect(jsonPath("$.questions[0].codingKind").value("CODE_COMPLETION"))
                .andExpect(jsonPath("$.questions[0].starterCode").isNotEmpty())
                .andExpect(jsonPath("$.questions[0].sources[0].sourceType")
                        .value("MODEL_KNOWLEDGE"))
                .andExpect(jsonPath("$.questions[0].referenceAnswer").doesNotExist())
                .andExpect(jsonPath("$.questions[0].correctAnswers").doesNotExist());
    }

    @Test
    void weakPointCreatesAtMostOneGovernedReviewCandidate() throws Exception {
        Registration registration = registerUser();
        MvcResult goal = mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "掌握 Spring",
                                  "targetDate": "%s",
                                  "weeklyStudyHours": 10
                                }
                                """.formatted(LocalDate.now().plusMonths(2))))
                .andExpect(status().isCreated())
                .andReturn();
        String goalId = readId(goal);
        MvcResult plan = mockMvc.perform(post("/internal/confirmed-learning-plans")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "goalId": "%s",
                                  "idempotencyKey": "review-plan-%d",
                                  "title": "Spring 计划",
                                  "startDate": "%s",
                                  "endDate": "%s",
                                  "tasks": [{
                                    "title": "学习依赖注入",
                                    "scheduledDate": "%s",
                                    "estimatedMinutes": 60
                                  }]
                                }
                                """.formatted(
                                registration.userId(), goalId, System.nanoTime(),
                                LocalDate.now(), LocalDate.now().plusDays(7), LocalDate.now()
                        )))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode planJson = objectMapper.readTree(plan.getResponse().getContentAsString());
        String taskId = planJson.get("tasks").get(0).get("id").asText();

        MvcResult quiz = mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "taskId": "%s",
                                  "title": "依赖注入检查",
                                  "modelName": "test-model",
                                  "questions": [{
                                    "type": "SINGLE_CHOICE",
                                    "knowledgePoint": "依赖注入",
                                    "questionText": "正确答案？",
                                    "options": ["IoC", "GC"],
                                    "correctAnswers": ["IoC"],
                                    "explanation": "IoC"
                                  }]
                                }
                                """.formatted(registration.userId(), taskId)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode quizJson = objectMapper.readTree(quiz.getResponse().getContentAsString());
        String quizId = quizJson.get("id").asText();
        String questionId = quizJson.get("questions").get(0).get("id").asText();
        MvcResult attempt = mockMvc.perform(post("/api/quizzes/{id}/attempts", quizId)
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "weak-attempt",
                                  "answers": [{
                                    "questionId": "%s",
                                    "selectedAnswers": ["GC"]
                                  }]
                                }
                                """.formatted(questionId)))
                .andExpect(status().isCreated())
                .andReturn();
        String attemptId = readId(attempt);

        mockMvc.perform(get("/internal/plan-adjustments/by-key")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .param("ownerId", registration.userId())
                        .param("idempotencyKey", "review:" + attemptId + ":依赖注入"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.operations.length()").value(1))
                .andExpect(jsonPath("$.operations[0].taskKind").value("REVIEW"));

        mockMvc.perform(get("/api/agent-executions")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("WAITING_AUTHORIZATION"));
    }

    @Test
    void codingAnswerIsLeasedEvaluatedAndIdempotentlyReturned() throws Exception {
        Registration registration = registerUser();
        MvcResult quiz = mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "title": "代码评估",
                                  "modelName": "test-model",
                                  "questions": [{
                                    "type": "CODING",
                                    "difficulty": "EASY",
                                    "codingKind": "CODE_COMPLETION",
                                    "language": "JAVA",
                                    "knowledgePoint": "Java 方法",
                                    "questionText": "实现 add",
                                    "starterCode": "int add(int a,int b){}",
                                    "correctAnswers": ["return a+b;"],
                                    "explanation": "返回两数之和",
                                    "rubric": {
                                      "correctness": 40,
                                      "completeness": 25,
                                      "edgeCases": 20,
                                      "clarityEfficiency": 15
                                    },
                                    "referenceAnswer": "int add(int a,int b){return a+b;}",
                                    "sources": [{
                                      "sourceType": "MODEL_KNOWLEDGE",
                                      "title": "Java基础",
                                      "locator": "模型常识",
                                      "snippet": "方法返回计算结果"
                                    }]
                                  }]
                                }
                                """.formatted(registration.userId())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode quizJson = objectMapper.readTree(quiz.getResponse().getContentAsString());
        String quizId = quizJson.get("id").asText();
        String questionId = quizJson.get("questions").get(0).get("id").asText();

        String submission = """
                {
                  "idempotencyKey": "coding-attempt-1",
                  "answers": [{
                    "questionId": "%s",
                    "codeAnswer": "int add(int a,int b){return a+b;}"
                  }]
                }
                """.formatted(questionId);
        MvcResult attempt = mockMvc.perform(post("/api/quizzes/{id}/attempts", quizId)
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EVALUATING"))
                .andReturn();
        String attemptId = readId(attempt);

        mockMvc.perform(post("/api/quizzes/{id}/attempts", quizId)
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(attemptId));

        MvcResult claimed = mockMvc.perform(post("/internal/coding-evaluation-jobs/claim")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"coding-worker","leaseSeconds":60}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(attemptId))
                .andExpect(jsonPath("$.ownerId").value(registration.userId()))
                .andExpect(jsonPath("$.answers[0].codeAnswer").isNotEmpty())
                .andReturn();
        String jobId = objectMapper.readTree(claimed.getResponse().getContentAsString())
                .get("jobId").asText();

        mockMvc.perform(post("/internal/coding-evaluation-jobs/{id}/heartbeat", jobId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"coding-worker","leaseSeconds":60}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/internal/coding-evaluation-jobs/{id}/complete", jobId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "coding-worker",
                                  "evaluations": [{
                                    "questionId": "%s",
                                    "score": 90,
                                    "correctness": 38,
                                    "completeness": 23,
                                    "edgeCases": 15,
                                    "clarityEfficiency": 14,
                                    "issues": [],
                                    "feedback": "逻辑正确。",
                                    "suggestedCode": "int add(int a,int b){return a+b;}"
                                  }]
                                }
                                """.formatted(questionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GRADED"));

        mockMvc.perform(get("/api/quiz-attempts/{id}", attemptId)
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GRADED"))
                .andExpect(jsonPath("$.results[0].evaluationMethod").value("AI_EVALUATED"))
                .andExpect(jsonPath("$.warning").value(
                        "未执行代码，不保证代码可以编译或运行"
                ));
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "quiz-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "测验用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(
                response.get("user").get("id").asText(),
                response.get("accessToken").asText()
        );
    }

    private String readId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private record Registration(String userId, String token) {
    }
}
