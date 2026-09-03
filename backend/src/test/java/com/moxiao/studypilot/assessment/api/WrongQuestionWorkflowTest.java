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
import com.moxiao.studypilot.assessment.application.QuizService;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionEntryJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionEventJpaRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WrongQuestionWorkflowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired QuizService quizService;
    @Autowired WrongQuestionEntryJpaRepository entryRepository;
    @Autowired WrongQuestionEventJpaRepository eventRepository;

    @Test
    void wrongChoiceIsCollectedAndLaterCorrectAnswerMastersIt() throws Exception {
        Registration registration = registerUser();
        JsonNode quiz = createChoiceQuiz(registration);
        String quizId = quiz.get("id").asText();
        String questionId = quiz.get("questions").get(0).get("id").asText();

        submit(registration, quizId, questionId, "HashMap", "wrong-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.results[0].questionText").value("哪个集合天然去重？"))
                .andExpect(jsonPath("$.results[0].selectedAnswers[0]").value("HashMap"))
                .andExpect(jsonPath("$.results[0].correctAnswers[0]").value("HashSet"));

        mockMvc.perform(get("/api/wrong-questions")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].questionText").value("哪个集合天然去重？"))
                .andExpect(jsonPath("$.items[0].latestSelectedAnswers[0]").value("HashMap"))
                .andExpect(jsonPath("$.items[0].correctAnswers[0]").value("HashSet"))
                .andExpect(jsonPath("$.items[0].explanation").value("Set 不允许重复元素。"));

        submit(registration, quizId, questionId, "HashSet", "correct-2")
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/wrong-questions")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/wrong-questions")
                        .header("Authorization", "Bearer " + registration.token())
                        .param("status", "MASTERED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].status").value("MASTERED"));
    }

    @Test
    void historicalBackfillIsIdempotentAndRemainsOwnerIsolated() throws Exception {
        Registration owner = registerUser();
        JsonNode quiz = createChoiceQuiz(owner);
        String questionId = quiz.get("questions").get(0).get("id").asText();
        submit(owner, quiz.get("id").asText(), questionId, "HashMap", "historical-wrong")
                .andExpect(status().isCreated());

        eventRepository.deleteAll();
        entryRepository.deleteAll();
        quizService.backfillWrongQuestions();
        quizService.backfillWrongQuestions();

        mockMvc.perform(get("/api/wrong-questions")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].wrongCount").value(1));

        Registration stranger = registerUser();
        mockMvc.perform(get("/api/wrong-questions")
                        .header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            Registration registration,
            String quizId,
            String questionId,
            String answer,
            String key
    ) throws Exception {
        return mockMvc.perform(post("/api/quizzes/{id}/attempts", quizId)
                .header("Authorization", "Bearer " + registration.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "idempotencyKey": "%s",
                          "answers": [{
                            "questionId": "%s",
                            "selectedAnswers": ["%s"]
                          }]
                        }
                        """.formatted(key, questionId, answer)));
    }

    private JsonNode createChoiceQuiz(Registration registration) throws Exception {
        MvcResult result = mockMvc.perform(post("/internal/quizzes")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "title": "Java 集合测验",
                                  "modelName": "test-model",
                                  "questions": [{
                                    "type": "SINGLE_CHOICE",
                                    "knowledgePoint": "Set 去重",
                                    "questionText": "哪个集合天然去重？",
                                    "options": ["ArrayList", "HashMap", "HashSet"],
                                    "correctAnswers": ["HashSet"],
                                    "explanation": "Set 不允许重复元素。"
                                  }]
                                }
                                """.formatted(registration.userId())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "wrong-book-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "错题用户"
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

    private record Registration(String userId, String token) {
    }
}
