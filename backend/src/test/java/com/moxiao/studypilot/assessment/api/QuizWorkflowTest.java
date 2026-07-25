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
