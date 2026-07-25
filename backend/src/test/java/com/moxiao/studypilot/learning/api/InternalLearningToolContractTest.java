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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InternalLearningToolContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void agentCanReadLearningContextAndCreatePlanDraft() throws Exception {
        Registration registration = registerUser();
        String goalId = createGoal(registration.token());

        mockMvc.perform(get("/internal/users/{ownerId}/learning-context", registration.userId())
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goals[0].id").value(goalId))
                .andExpect(jsonPath("$.tasks").isEmpty());

        mockMvc.perform(post("/internal/learning-plans")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "goalId": "%s",
                                  "title": "Agent 生成的计划草案",
                                  "startDate": "%s",
                                  "endDate": "%s"
                                }
                                """.formatted(
                                        registration.userId(),
                                        goalId,
                                        LocalDate.now().plusDays(1),
                                        LocalDate.now().plusDays(30)
                                )))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));
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
                                """.formatted(LocalDate.now().plusMonths(4))))
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
                                  "email": "tool-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "工具契约用户"
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
