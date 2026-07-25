package com.moxiao.studypilot.learning.api;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConfirmedLearningPlanContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void atomicallyCreatesConfirmedPlanAndTasksOnlyOnce() throws Exception {
        Registration registration = registerUser();
        String goalId = createGoal(registration.token());
        LocalDate start = LocalDate.now().plusDays(1);
        String requestBody = confirmedPlanRequest(
                registration.userId(),
                goalId,
                "plan-generation:thread-1-" + System.nanoTime(),
                start,
                start.plusDays(30),
                start
        );

        MvcResult first = createConfirmedPlan(requestBody);
        MvcResult duplicate = createConfirmedPlan(requestBody);
        JsonNode firstResponse = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode duplicateResponse = objectMapper.readTree(
                duplicate.getResponse().getContentAsString()
        );

        assertEquals(
                firstResponse.get("plan").get("id").asText(),
                duplicateResponse.get("plan").get("id").asText()
        );
        assertEquals(
                firstResponse.get("tasks").get(0).get("id").asText(),
                duplicateResponse.get("tasks").get(0).get("id").asText()
        );

        mockMvc.perform(get("/api/learning-plans")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"));

        mockMvc.perform(get("/api/learning-tasks")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void invalidTaskDateLeavesNoPartialPlan() throws Exception {
        Registration registration = registerUser();
        String goalId = createGoal(registration.token());
        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = start.plusDays(7);

        mockMvc.perform(post("/internal/confirmed-learning-plans")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmedPlanRequest(
                                registration.userId(),
                                goalId,
                                "plan-generation:invalid-" + System.nanoTime(),
                                start,
                                end,
                                end.plusDays(1)
                        )))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/learning-plans")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    private MvcResult createConfirmedPlan(String body) throws Exception {
        return mockMvc.perform(post("/internal/confirmed-learning-plans")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plan.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.tasks[0].title").value("学习依赖注入"))
                .andReturn();
    }

    private String confirmedPlanRequest(
            String ownerId,
            String goalId,
            String idempotencyKey,
            LocalDate start,
            LocalDate end,
            LocalDate taskDate
    ) {
        return """
                {
                  "ownerId": "%s",
                  "goalId": "%s",
                  "idempotencyKey": "%s",
                  "title": "Java 后端计划",
                  "startDate": "%s",
                  "endDate": "%s",
                  "tasks": [
                    {
                      "title": "学习依赖注入",
                      "scheduledDate": "%s",
                      "estimatedMinutes": 60
                    }
                  ]
                }
                """.formatted(ownerId, goalId, idempotencyKey, start, end, taskDate);
    }

    private String createGoal(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Java + AI",
                                  "targetDate": "%s",
                                  "weeklyStudyHours": 10
                                }
                                """.formatted(LocalDate.now().plusMonths(6))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "confirmed-plan-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "计划确认用户"
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
