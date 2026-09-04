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

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GovernedAgentToolWorkflowTest {
    private static final String INTERNAL_TOKEN = "test-internal-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void highRiskTaskWriteWaitsForDedicatedConfirmationAndExecutesOnce() throws Exception {
        Registration owner = registerUser();
        String taskId = createConfirmedTask(owner);
        String idempotencyKey = "agent-tool-action:" + System.nanoTime();

        MvcResult preview = invokeTaskTool(owner.userId(), taskId, idempotencyKey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.action.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();
        String actionId = objectMapper.readTree(preview.getResponse().getContentAsString())
                .get("action").get("actionId").asText();

        listTasks(owner.token())
                .andExpect(jsonPath("$[0].status").value("TODO"))
                .andExpect(jsonPath("$[0].version").value(1));

        confirm(owner.userId(), actionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.status").value("COMPLETED"));
        confirm(owner.userId(), actionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        listTasks(owner.token())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].version").value(2));
        mockMvc.perform(get("/api/learning-tasks/{id}/history", taskId)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void writeToolRequiresIdempotencyAndRejectsChatLikeConfirmationArguments() throws Exception {
        Registration owner = registerUser();
        String taskId = createConfirmedTask(owner);
        String bodyWithoutKey = """
                {
                  "ownerId":"%s",
                  "arguments":{"taskId":"%s","expectedVersion":1,"status":"COMPLETED"}
                }
                """.formatted(owner.userId(), taskId);
        mockMvc.perform(post("/internal/agent-tools/learning.task.update/invoke")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutKey))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/internal/agent-tools/learning.task.update/invoke")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId":"%s",
                                  "idempotencyKey":"chat-confirm:%d",
                                  "arguments":{
                                    "taskId":"%s",
                                    "expectedVersion":1,
                                    "status":"COMPLETED",
                                    "confirm":true
                                  }
                                }
                                """.formatted(owner.userId(), System.nanoTime(), taskId)))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions invokeTaskTool(
            String ownerId, String taskId, String idempotencyKey
    ) throws Exception {
        return mockMvc.perform(post("/internal/agent-tools/learning.task.update/invoke")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "ownerId":"%s",
                          "idempotencyKey":"%s",
                          "arguments":{
                            "taskId":"%s",
                            "expectedVersion":1,
                            "status":"COMPLETED",
                            "reason":"用户明确表示已经完成"
                          }
                        }
                        """.formatted(ownerId, idempotencyKey, taskId)));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(
            String ownerId, String actionId
    ) throws Exception {
        return mockMvc.perform(post("/internal/agent-tool-actions/{id}/confirm", actionId)
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerId\":\"" + ownerId + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions listTasks(String token)
            throws Exception {
        return mockMvc.perform(get("/api/learning-tasks")
                .header("Authorization", "Bearer " + token));
    }

    private String createConfirmedTask(Registration owner) throws Exception {
        String goalId = createGoal(owner.token());
        LocalDate date = LocalDate.now().plusDays(1);
        MvcResult result = mockMvc.perform(post("/internal/confirmed-learning-plans")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId":"%s",
                                  "goalId":"%s",
                                  "idempotencyKey":"governed-tool-plan-%d",
                                  "title":"统一工具测试计划",
                                  "startDate":"%s",
                                  "endDate":"%s",
                                  "tasks":[{
                                    "title":"完成统一工具测试",
                                    "scheduledDate":"%s",
                                    "estimatedMinutes":60
                                  }]
                                }
                                """.formatted(owner.userId(), goalId, System.nanoTime(),
                                date, date.plusDays(7), date)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("tasks").get(0).get("id").asText();
    }

    private String createGoal(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"统一 Agent 测试","targetDate":"%s","weeklyStudyHours":10}
                                """.formatted(LocalDate.now().plusMonths(2))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"governed-tool-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"统一工具用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(body.get("user").get("id").asText(),
                body.get("accessToken").asText());
    }

    private record Registration(String userId, String token) {
    }
}
