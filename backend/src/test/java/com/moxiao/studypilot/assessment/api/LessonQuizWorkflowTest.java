package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LessonQuizWorkflowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CourseCatalogImporter importer;

    @Test
    void lessonQuizDoesNotRequireTaskAndCompletesPracticeAfterPassing() throws Exception {
        importer.importCatalog();
        Registration user = register();
        passCheckpoint(user.token());
        markVideoAndReading(user.token());

        JsonNode quiz = createLessonQuiz(user.id());
        String quizId = quiz.get("id").asText();
        StringBuilder answers = new StringBuilder();
        for (int index = 0; index < quiz.get("questions").size(); index++) {
            if (index > 0) {
                answers.append(',');
            }
            JsonNode question = quiz.get("questions").get(index);
            answers.append("""
                    {"questionId":"%s","selectedAnswers":["正确"]}
                    """.formatted(question.get("id").asText()));
        }

        mockMvc.perform(get("/api/quizzes/{quizId}", quizId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lessonId").value("lesson-rest-controller"))
                .andExpect(jsonPath("$.taskId").doesNotExist());

        mockMvc.perform(post("/api/quizzes/{quizId}/attempts", quizId)
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"lesson-quiz-pass","answers":[%s]}
                                """.formatted(answers)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("GRADED"))
                .andExpect(jsonPath("$.score").value(100.0));

        mockMvc.perform(get("/api/lessons/lesson-rest-controller")
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress.practiceCompleted").value(true))
                .andExpect(jsonPath("$.progress.status").value("COMPLETED"));
    }

    private JsonNode createLessonQuiz(String ownerId) throws Exception {
        String question = """
                {
                  "type":"SINGLE_CHOICE",
                  "difficulty":"EASY",
                  "knowledgePoint":"REST Controller",
                  "questionText":"Controller 的职责是什么？",
                  "options":["正确","错误"],
                  "correctAnswers":["正确"],
                  "explanation":"Controller 负责 HTTP 契约。",
                  "sources":[{
                    "sourceType":"LESSON_SOURCE",
                    "title":"课时讲义",
                    "locator":"request-flow",
                    "snippet":"Controller 负责 HTTP 契约"
                  }]
                }
                """;
        MvcResult result = mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId":"%s",
                                  "lessonId":"lesson-rest-controller",
                                  "title":"课时测验",
                                  "modelName":"deepseek-v4-pro",
                                  "questions":[%s,%s,%s,%s,%s]
                                }
                                """.formatted(
                                ownerId, question, question, question, question, question
                        )))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void passCheckpoint(String token) throws Exception {
        mockMvc.perform(post(
                                "/api/lessons/lesson-rest-controller/checkpoints/checkpoint/attempts"
                        )
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedOption\":0}"))
                .andExpect(status().isOk());
    }

    private void markVideoAndReading(String token) throws Exception {
        mockMvc.perform(put("/api/lessons/lesson-rest-controller/progress")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "videoCompleted":true,
                                  "readingCompleted":true,
                                  "lastSectionKey":"summary"
                                }
                                """))
                .andExpect(status().isOk());
    }

    private Registration register() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"lesson-quiz-%s@example.com",
                                  "password":"Password123!",
                                  "displayName":"课时测验用户"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(
                body.get("user").get("id").asText(),
                body.get("accessToken").asText()
        );
    }

    private record Registration(String id, String token) {
    }
}
