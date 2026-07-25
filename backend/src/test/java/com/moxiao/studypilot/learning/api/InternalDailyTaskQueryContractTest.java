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
class InternalDailyTaskQueryContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void internalQueryFiltersTasksByOwnerAndDate() throws Exception {
        LocalDate today = LocalDate.now();
        Registration firstUser = registerUser("first");
        Registration secondUser = registerUser("second");

        createConfirmedPlanWithTasks(
                firstUser,
                "第一个用户今天的任务",
                today,
                "第一个用户明天的任务",
                today.plusDays(1)
        );
        createConfirmedPlanWithTasks(
                secondUser,
                "第二个用户今天的任务",
                today,
                "第二个用户明天的任务",
                today.plusDays(1)
        );

        mockMvc.perform(get(
                                "/internal/users/{ownerId}/learning-tasks",
                                firstUser.userId()
                        )
                        .queryParam("date", today.toString())
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("第一个用户今天的任务"))
                .andExpect(jsonPath("$[0].scheduledDate").value(today.toString()));
    }

    @Test
    void internalQueryRequiresTokenAndIsoDate() throws Exception {
        Registration registration = registerUser("security");

        mockMvc.perform(get(
                                "/internal/users/{ownerId}/learning-tasks",
                                registration.userId()
                        )
                        .queryParam("date", LocalDate.now().toString()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(
                                "/internal/users/{ownerId}/learning-tasks",
                                registration.userId()
                        )
                        .queryParam("date", "not-a-date")
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isBadRequest());
    }

    private void createConfirmedPlanWithTasks(
            Registration registration,
            String firstTitle,
            LocalDate firstDate,
            String secondTitle,
            LocalDate secondDate
    ) throws Exception {
        String goalId = createGoal(registration.token());
        mockMvc.perform(post("/internal/confirmed-learning-plans")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "goalId": "%s",
                                  "idempotencyKey": "daily-query-%d",
                                  "title": "任务查询测试计划",
                                  "startDate": "%s",
                                  "endDate": "%s",
                                  "tasks": [
                                    {
                                      "title": "%s",
                                      "scheduledDate": "%s",
                                      "estimatedMinutes": 30
                                    },
                                    {
                                      "title": "%s",
                                      "scheduledDate": "%s",
                                      "estimatedMinutes": 30
                                    }
                                  ]
                                }
                                """.formatted(
                                registration.userId(),
                                goalId,
                                System.nanoTime(),
                                firstDate,
                                secondDate,
                                firstTitle,
                                firstDate,
                                secondTitle,
                                secondDate
                        )))
                .andExpect(status().isCreated());
    }

    private String createGoal(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "每日任务闭环",
                                  "targetDate": "%s",
                                  "weeklyStudyHours": 10
                                }
                                """.formatted(LocalDate.now().plusMonths(2))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }

    private Registration registerUser(String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "daily-query-%s-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "每日任务用户"
                                }
                                """.formatted(label, System.nanoTime())))
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
