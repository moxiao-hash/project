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
class PlanAdjustmentContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void persistsLowRiskDraftIdempotentlyAndReturnsIt() throws Exception {
        Setup setup = createPlan();
        String key = "plan-adjustment:test:" + System.nanoTime();
        String body = """
                {
                  "ownerId": "%s",
                  "planId": "%s",
                  "idempotencyKey": "%s",
                  "analysisDate": "%s",
                  "triggerType": "USER_REQUEST",
                  "signals": ["OVERDUE_TASKS"],
                  "summary": "顺延一个逾期任务",
                  "operations": [
                    {
                      "type": "RESCHEDULE_TASK",
                      "taskId": "%s",
                      "expectedVersion": 1,
                      "scheduledDate": "%s"
                    }
                  ]
                }
                """.formatted(
                setup.ownerId(),
                setup.planId(),
                key,
                LocalDate.now(),
                setup.taskId(),
                LocalDate.now().plusDays(2)
        );

        String firstId = createAdjustment(body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.status").value("DRAFT_READY"))
                .andExpect(jsonPath("$.operations[0].taskId").value(setup.taskId()))
                .andReturn().getResponse().getContentAsString();
        String adjustmentId = objectMapper.readTree(firstId).get("id").asText();

        createAdjustment(body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(adjustmentId));

        mockMvc.perform(get("/internal/plan-adjustments/{id}", adjustmentId)
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey").value(key));
    }

    @Test
    void emptyOperationsPersistAsNoChange() throws Exception {
        Setup setup = createPlan();

        createAdjustment("""
                {
                  "ownerId": "%s",
                  "planId": "%s",
                  "idempotencyKey": "plan-adjustment:no-change:%d",
                  "analysisDate": "%s",
                  "triggerType": "NIGHTLY_CHECK",
                  "signals": [],
                  "summary": "没有需要调整的偏差",
                  "operations": []
                }
                """.formatted(
                setup.ownerId(),
                setup.planId(),
                System.nanoTime(),
                LocalDate.now()
        ))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NO_CHANGE"));
    }

    private org.springframework.test.web.servlet.ResultActions createAdjustment(
            String body
    ) throws Exception {
        return mockMvc.perform(post("/internal/plan-adjustments")
                .header("X-Internal-Service-Token", "test-internal-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Setup createPlan() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "plan-adjustment-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "计划调整用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode auth = objectMapper.readTree(registration.getResponse().getContentAsString());
        String ownerId = auth.get("user").get("id").asText();
        String token = auth.get("accessToken").asText();
        MvcResult goal = mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "计划调整目标",
                                  "targetDate": "%s",
                                  "weeklyStudyHours": 10
                                }
                                """.formatted(LocalDate.now().plusMonths(3))))
                .andExpect(status().isCreated())
                .andReturn();
        String goalId = objectMapper.readTree(goal.getResponse().getContentAsString())
                .get("id").asText();
        MvcResult plan = mockMvc.perform(post("/internal/confirmed-learning-plans")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "goalId": "%s",
                                  "idempotencyKey": "adjustment-plan-%d",
                                  "title": "可调整计划",
                                  "startDate": "%s",
                                  "endDate": "%s",
                                  "tasks": [
                                    {
                                      "title": "需要调整的任务",
                                      "scheduledDate": "%s",
                                      "estimatedMinutes": 60
                                    }
                                  ]
                                }
                                """.formatted(
                                ownerId,
                                goalId,
                                System.nanoTime(),
                                LocalDate.now(),
                                LocalDate.now().plusDays(7),
                                LocalDate.now()
                        )))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = objectMapper.readTree(plan.getResponse().getContentAsString());
        return new Setup(
                ownerId,
                response.get("plan").get("id").asText(),
                response.get("tasks").get(0).get("id").asText()
        );
    }

    private record Setup(String ownerId, String planId, String taskId) {
    }
}
