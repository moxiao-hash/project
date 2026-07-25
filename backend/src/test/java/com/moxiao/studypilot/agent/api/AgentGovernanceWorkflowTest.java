package com.moxiao.studypilot.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AgentGovernanceWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void grantControlsIdempotentAuditedAgentExecution() throws Exception {
        Registration registration = registerUser();

        mockMvc.perform(post("/api/agent-grants")
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scopes": ["SMALL_PLAN_ADJUSTMENT", "QUIZ_GENERATION"],
                                  "expiresAt": "%s"
                                }
                                """.formatted(Instant.now().plusSeconds(3600))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true));

        String requestBody = """
                {
                  "ownerId": "%s",
                  "idempotencyKey": "plan-1:2026-07-25:CHECK_IN",
                  "executionType": "PLAN_ADJUSTMENT",
                  "triggerType": "CHECK_IN",
                  "riskLevel": "LOW",
                  "requiredScope": "SMALL_PLAN_ADJUSTMENT",
                  "summary": "根据打卡结果微调后续任务"
                }
                """.formatted(registration.userId());
        MvcResult first = createExecution(requestBody);
        MvcResult duplicate = createExecution(requestBody);
        String executionId = readId(first);
        assertEquals(executionId, readId(duplicate));

        mockMvc.perform(patch("/internal/agent-executions/{id}", executionId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "SUCCEEDED",
                                  "resultSummary": "已顺延 1 个未完成任务",
                                  "modelName": "test-model",
                                  "promptTokens": 120,
                                  "completionTokens": 40,
                                  "latencyMs": 350,
                                  "estimatedCost": 0.002
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        mockMvc.perform(get("/api/agent-executions")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].resultSummary").value("已顺延 1 个未完成任务"));

        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("EXECUTION_STATUS_CHANGED"));
    }

    @Test
    void highRiskExecutionWaitsForExplicitConfirmation() throws Exception {
        Registration registration = registerUser();

        MvcResult result = createExecution("""
                {
                  "ownerId": "%s",
                  "idempotencyKey": "high-risk-%d",
                  "executionType": "PLAN_ADJUSTMENT",
                  "triggerType": "USER_REQUEST",
                  "riskLevel": "HIGH",
                  "requiredScope": "LARGE_PLAN_ADJUSTMENT",
                  "summary": "大范围重排计划"
                }
                """.formatted(registration.userId(), System.nanoTime()));
        String executionId = readId(result);

        mockMvc.perform(post("/api/agent-executions/{id}/confirm", executionId)
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void internalAgentCanRecordAnExplicitPlanGenerationConfirmation() throws Exception {
        Registration registration = registerUser();

        MvcResult result = createExecution("""
                {
                  "ownerId": "%s",
                  "idempotencyKey": "plan-generation-confirm-%d",
                  "executionType": "PLAN_GENERATION",
                  "triggerType": "USER_REQUEST",
                  "riskLevel": "HIGH",
                  "requiredScope": "PLAN_GENERATION",
                  "summary": "生成学习计划并等待用户确认"
                }
                """.formatted(registration.userId(), System.nanoTime()));
        String executionId = readId(result);

        mockMvc.perform(post("/internal/agent-executions/{id}/confirm", executionId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s"
                                }
                                """.formatted(registration.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("EXECUTION_CONFIRMED"));
    }

    @Test
    void taskStatusChangeAlwaysWaitsForExplicitConfirmationAndIsAudited() throws Exception {
        Registration registration = registerUser();

        MvcResult result = createExecution("""
                {
                  "ownerId": "%s",
                  "idempotencyKey": "task-action-%d",
                  "executionType": "TASK_STATUS_CHANGE",
                  "triggerType": "USER_REQUEST",
                  "riskLevel": "HIGH",
                  "requiredScope": "TASK_MANAGEMENT",
                  "summary": "修改学习任务状态并等待用户确认"
                }
                """.formatted(registration.userId(), System.nanoTime()));
        String executionId = readId(result);

        mockMvc.perform(post("/internal/agent-executions/{id}/confirm", executionId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s"
                                }
                                """.formatted(registration.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.executionType").value("TASK_STATUS_CHANGE"))
                .andExpect(jsonPath("$.requiredScope").value("TASK_MANAGEMENT"));

        mockMvc.perform(patch("/internal/agent-executions/{id}", executionId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "SUCCEEDED",
                                  "resultSummary": "任务状态已修改"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("EXECUTION_STATUS_CHANGED"))
                .andExpect(jsonPath("$[1].action").value("EXECUTION_CONFIRMED"))
                .andExpect(jsonPath("$[2].action").value("EXECUTION_CREATED"));
    }

    private MvcResult createExecution(String body) throws Exception {
        return mockMvc.perform(post("/internal/agent-executions")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "agent-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "Agent 用户"
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
