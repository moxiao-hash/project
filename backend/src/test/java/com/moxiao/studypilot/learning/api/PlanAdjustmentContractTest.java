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
import java.time.Instant;
import java.time.ZoneId;

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

        mockMvc.perform(get("/internal/plan-adjustments/by-key")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .param("ownerId", setup.ownerId())
                        .param("idempotencyKey", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(adjustmentId));
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

    @Test
    void authorizedLowRiskAdjustmentExecutesAtomicallyAndIdempotently() throws Exception {
        Setup setup = createPlan();
        createSmallAdjustmentGrant(setup);
        String executionId = createPendingExecution(setup, "authorized");
        LocalDate newDate = LocalDate.now().plusDays(2);
        String adjustmentId = createExecutableAdjustment(
                setup,
                executionId,
                1,
                newDate,
                "authorized"
        );

        executeAdjustment(setup, adjustmentId, executionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.beforePlanVersion").value(2))
                .andExpect(jsonPath("$.afterPlanVersion").value(3));

        mockMvc.perform(get("/api/learning-tasks")
                        .header("Authorization", "Bearer " + setup.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scheduledDate").value(newDate.toString()))
                .andExpect(jsonPath("$[0].version").value(2));

        executeAdjustment(setup, adjustmentId, executionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.afterPlanVersion").value(3));

        mockMvc.perform(get("/api/learning-plans/{planId}/versions", setup.planId())
                        .header("Authorization", "Bearer " + setup.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].version").value(3))
                .andExpect(jsonPath("$[0].snapshotJson").value(
                        org.hamcrest.Matchers.containsString(setup.taskId())
                ));

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + setup.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PLAN_ADJUSTED"));

        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + setup.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].details").value(
                        org.hamcrest.Matchers.hasItem("状态变更为 SUCCEEDED")
                ));
    }

    @Test
    void staleTaskVersionRejectsWholeAdjustmentAndMarksDraftFailed() throws Exception {
        Setup setup = createPlan();
        createSmallAdjustmentGrant(setup);
        String executionId = createPendingExecution(setup, "stale");
        String adjustmentId = createExecutableAdjustment(
                setup,
                executionId,
                99,
                LocalDate.now().plusDays(2),
                "stale"
        );

        executeAdjustment(setup, adjustmentId, executionId)
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/learning-tasks")
                        .header("Authorization", "Bearer " + setup.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scheduledDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$[0].version").value(1));

        mockMvc.perform(get("/internal/plan-adjustments/{id}", adjustmentId)
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + setup.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("AGENT_FAILED"));
    }

    @Test
    void nightlyCandidatesAreReturnedUntilDateIsPersisted() throws Exception {
        Setup setup = createPlan();
        Instant at = Instant.parse("2026-07-27T00:17:00Z");
        LocalDate analysisDate = at.atZone(ZoneId.of("Asia/Shanghai"))
                .toLocalDate()
                .minusDays(1);

        mockMvc.perform(get("/internal/plan-adjustments/nightly-candidates")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .param("at", at.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ownerId == '%s')].analysisDate"
                        .formatted(setup.ownerId()))
                        .value(analysisDate.toString()));

        createAdjustment("""
                {
                  "ownerId": "%s",
                  "planId": "%s",
                  "idempotencyKey": "plan-adjustment:nightly:%s:%s",
                  "analysisDate": "%s",
                  "triggerType": "NIGHTLY_CHECK",
                  "signals": [],
                  "summary": "已完成夜间偏差分析",
                  "operations": []
                }
                """.formatted(
                setup.ownerId(),
                setup.planId(),
                setup.ownerId(),
                analysisDate,
                analysisDate
        )).andExpect(status().isCreated());

        mockMvc.perform(get("/internal/plan-adjustments/nightly-candidates")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .param("at", at.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ownerId == '%s')]"
                        .formatted(setup.ownerId()))
                        .isEmpty());
    }

    private org.springframework.test.web.servlet.ResultActions createAdjustment(
            String body
    ) throws Exception {
        return mockMvc.perform(post("/internal/plan-adjustments")
                .header("X-Internal-Service-Token", "test-internal-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void createSmallAdjustmentGrant(Setup setup) throws Exception {
        mockMvc.perform(post("/api/agent-grants")
                        .header("Authorization", "Bearer " + setup.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scopes": ["SMALL_PLAN_ADJUSTMENT"],
                                  "expiresAt": "%s"
                                }
                                """.formatted(Instant.now().plusSeconds(3600))))
                .andExpect(status().isCreated());
    }

    private String createPendingExecution(Setup setup, String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/internal/agent-executions")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "idempotencyKey": "adjustment-execution-%s-%d",
                                  "executionType": "PLAN_ADJUSTMENT",
                                  "triggerType": "USER_REQUEST",
                                  "riskLevel": "LOW",
                                  "requiredScope": "SMALL_PLAN_ADJUSTMENT",
                                  "summary": "执行小范围计划调整"
                                }
                                """.formatted(setup.ownerId(), label, System.nanoTime())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }

    private String createExecutableAdjustment(
            Setup setup,
            String executionId,
            int taskVersion,
            LocalDate newDate,
            String label
    ) throws Exception {
        MvcResult result = createAdjustment("""
                {
                  "ownerId": "%s",
                  "planId": "%s",
                  "idempotencyKey": "plan-adjustment:execute:%s:%d",
                  "analysisDate": "%s",
                  "triggerType": "USER_REQUEST",
                  "signals": ["OVERDUE_TASKS"],
                  "summary": "顺延逾期任务",
                  "executionId": "%s",
                  "operations": [
                    {
                      "type": "RESCHEDULE_TASK",
                      "taskId": "%s",
                      "expectedVersion": %d,
                      "scheduledDate": "%s"
                    }
                  ]
                }
                """.formatted(
                setup.ownerId(),
                setup.planId(),
                label,
                System.nanoTime(),
                LocalDate.now(),
                executionId,
                setup.taskId(),
                taskVersion,
                newDate
        ))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions executeAdjustment(
            Setup setup,
            String adjustmentId,
            String executionId
    ) throws Exception {
        return mockMvc.perform(post(
                                "/internal/plan-adjustments/{id}/execute",
                                adjustmentId
                        )
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "executionId": "%s",
                                  "expectedPlanVersion": 2
                                }
                                """.formatted(setup.ownerId(), executionId)));
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
                token,
                response.get("plan").get("id").asText(),
                response.get("tasks").get(0).get("id").asText()
        );
    }

    private record Setup(String ownerId, String token, String planId, String taskId) {
    }
}
