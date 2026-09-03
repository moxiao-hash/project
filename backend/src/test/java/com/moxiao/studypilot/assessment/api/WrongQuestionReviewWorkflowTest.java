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
class WrongQuestionReviewWorkflowTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void remainingQuestionsCreateReusableReviewAndCorrectSubmissionClearsThem() throws Exception {
        Registration registration = registerUser();
        JsonNode sourceQuiz = createQuiz(registration);
        submitWrongAnswers(registration, sourceQuiz);

        String createBody = """
                {"idempotencyKey":"wrong-review-batch-1"}
                """;
        MvcResult created = mockMvc.perform(post("/api/wrong-question-reviews")
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.questionCount").value(2))
                .andExpect(jsonPath("$.remainingCount").value(2))
                .andReturn();
        JsonNode review = objectMapper.readTree(created.getResponse().getContentAsString());
        String reviewId = review.get("id").asText();
        String reviewQuizId = review.get("quizId").asText();

        mockMvc.perform(post("/api/wrong-question-reviews")
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(reviewId));

        MvcResult reviewQuizResult = mockMvc.perform(get("/api/quizzes/{id}", reviewQuizId)
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("WRONG_QUESTION_REVIEW"))
                .andExpect(jsonPath("$.questions.length()").value(2))
                .andExpect(jsonPath("$.questions[0].correctAnswers").doesNotExist())
                .andReturn();
        JsonNode questions = objectMapper.readTree(
                reviewQuizResult.getResponse().getContentAsString()).get("questions");

        mockMvc.perform(post("/api/quizzes/{id}/attempts", reviewQuizId)
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey":"review-answer-1",
                                  "answers":[
                                    {"questionId":"%s","selectedAnswers":["A"]},
                                    {"questionId":"%s","selectedAnswers":["A"]}
                                  ]
                                }
                                """.formatted(
                                questions.get(0).get("id").asText(),
                                questions.get(1).get("id").asText())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewProgress.clearedCount").value(2))
                .andExpect(jsonPath("$.reviewProgress.remainingCount").value(0));

        mockMvc.perform(get("/api/wrong-questions/summary")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount").value(0))
                .andExpect(jsonPath("$.masteredCount").value(2));
    }

    private void submitWrongAnswers(Registration registration, JsonNode quiz) throws Exception {
        JsonNode questions = quiz.get("questions");
        mockMvc.perform(post("/api/quizzes/{id}/attempts", quiz.get("id").asText())
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey":"source-wrong-attempt",
                                  "answers":[
                                    {"questionId":"%s","selectedAnswers":["B"]},
                                    {"questionId":"%s","selectedAnswers":["B"]}
                                  ]
                                }
                                """.formatted(
                                questions.get(0).get("id").asText(),
                                questions.get(1).get("id").asText())))
                .andExpect(status().isCreated());
    }

    private JsonNode createQuiz(Registration registration) throws Exception {
        MvcResult result = mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId":"%s",
                                  "title":"Java 基础章节",
                                  "modelName":"test-model",
                                  "questions":[
                                    {"type":"SINGLE_CHOICE","knowledgePoint":"变量",
                                     "questionText":"变量题","options":["A","B"],
                                     "correctAnswers":["A"],"explanation":"变量解析"},
                                    {"type":"SINGLE_CHOICE","knowledgePoint":"循环",
                                     "questionText":"循环题","options":["A","B"],
                                     "correctAnswers":["A"],"explanation":"循环解析"}
                                  ]
                                }
                                """.formatted(registration.userId())))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"wrong-review-%d@example.com",
                                 "password":"Password123!","displayName":"重做用户"}
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(response.get("user").get("id").asText(),
                response.get("accessToken").asText());
    }

    private record Registration(String userId, String token) {
    }
}
