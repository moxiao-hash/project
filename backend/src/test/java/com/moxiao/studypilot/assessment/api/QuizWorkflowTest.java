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

        mockMvc.perform(post("/api/quizzes/{id}/attempts", quizId)
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
                .andExpect(jsonPath("$.results[0].explanation").isNotEmpty());

        mockMvc.perform(get("/api/mastery")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].knowledgePoint").value("依赖注入"))
                .andExpect(jsonPath("$[0].score").value(100.0));
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
                                      "starterCode": "int add(int a, int b) { }",
                                      "correctAnswers": ["return a + b;"],
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
                .andExpect(jsonPath("$.questions[0].correctAnswers[0]")
                        .value("return a + b;"))
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
