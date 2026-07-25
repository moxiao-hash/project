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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InternalTaskStatusToolContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void agentCompletesTaskIdempotentlyAndRecordsOneChange() throws Exception {
        Registration owner = registerUser("owner");
        String taskId = createConfirmedTask(owner, "完成 Spring Boot 测试");
        String idempotencyKey = "task-action:complete:" + System.nanoTime();
        String request = """
                {
                  "ownerId": "%s",
                  "idempotencyKey": "%s",
                  "expectedVersion": 1,
                  "status": "COMPLETED",
                  "reason": "用户在对话中明确表示已完成"
                }
                """.formatted(owner.userId(), idempotencyKey);

        changeTaskStatus(taskId, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        changeTaskStatus(taskId, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(get("/api/learning-tasks/{taskId}/history", taskId)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fromStatus").value("TODO"))
                .andExpect(jsonPath("$[0].toStatus").value("COMPLETED"));

        changeTaskStatus(taskId, """
                {
                  "ownerId": "%s",
                  "idempotencyKey": "%s",
                  "expectedVersion": 1,
                  "status": "SKIPPED",
                  "reason": "尝试复用相同幂等键执行其他动作"
                }
                """.formatted(owner.userId(), idempotencyKey))
                .andExpect(status().isConflict());
    }

    @Test
    void agentTaskChangeRejectsStaleVersionAndWrongOwner() throws Exception {
        Registration owner = registerUser("version-owner");
        Registration other = registerUser("other");
        String taskId = createConfirmedTask(owner, "版本校验任务");

        changeTaskStatus(taskId, completedRequest(
                owner.userId(),
                "task-action:first:" + System.nanoTime(),
                1
        )).andExpect(status().isOk());

        changeTaskStatus(taskId, """
                {
                  "ownerId": "%s",
                  "idempotencyKey": "task-action:stale:%d",
                  "expectedVersion": 1,
                  "status": "SKIPPED",
                  "reason": "基于旧版本操作"
                }
                """.formatted(owner.userId(), System.nanoTime()))
                .andExpect(status().isConflict());

        String otherTaskId = createConfirmedTask(owner, "归属校验任务");
        changeTaskStatus(otherTaskId, completedRequest(
                other.userId(),
                "task-action:wrong-owner:" + System.nanoTime(),
                1
        )).andExpect(status().isNotFound());
    }

    @Test
    void agentDeferralRequiresReasonAndFutureDate() throws Exception {
        Registration owner = registerUser("defer-owner");
        String taskId = createConfirmedTask(owner, "延期校验任务");

        changeTaskStatus(taskId, """
                {
                  "ownerId": "%s",
                  "idempotencyKey": "task-action:no-date:%d",
                  "expectedVersion": 1,
                  "status": "DEFERRED",
                  "reason": "今天时间不足"
                }
                """.formatted(owner.userId(), System.nanoTime()))
                .andExpect(status().isBadRequest());

        changeTaskStatus(taskId, """
                {
                  "ownerId": "%s",
                  "idempotencyKey": "task-action:no-reason:%d",
                  "expectedVersion": 1,
                  "status": "DEFERRED",
                  "scheduledDate": "%s"
                }
                """.formatted(
                owner.userId(),
                System.nanoTime(),
                LocalDate.now().plusDays(2)
        )).andExpect(status().isBadRequest());

        changeTaskStatus(taskId, """
                {
                  "ownerId": "%s",
                  "idempotencyKey": "task-action:past-date:%d",
                  "expectedVersion": 1,
                  "status": "DEFERRED",
                  "scheduledDate": "%s",
                  "reason": "测试非法日期"
                }
                """.formatted(owner.userId(), System.nanoTime(), LocalDate.now()))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions changeTaskStatus(
            String taskId,
            String request
    ) throws Exception {
        return mockMvc.perform(patch("/internal/learning-tasks/{taskId}/status", taskId)
                .header("X-Internal-Service-Token", "test-internal-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request));
    }

    private String completedRequest(
            String ownerId,
            String idempotencyKey,
            int expectedVersion
    ) {
        return """
                {
                  "ownerId": "%s",
                  "idempotencyKey": "%s",
                  "expectedVersion": %d,
                  "status": "COMPLETED",
                  "reason": "用户明确确认完成"
                }
                """.formatted(ownerId, idempotencyKey, expectedVersion);
    }

    private String createConfirmedTask(Registration registration, String title) throws Exception {
        String goalId = createGoal(registration.token());
        LocalDate taskDate = LocalDate.now().plusDays(1);
        MvcResult result = mockMvc.perform(post("/internal/confirmed-learning-plans")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "goalId": "%s",
                                  "idempotencyKey": "task-tool-plan-%d",
                                  "title": "Agent 任务操作测试计划",
                                  "startDate": "%s",
                                  "endDate": "%s",
                                  "tasks": [
                                    {
                                      "title": "%s",
                                      "scheduledDate": "%s",
                                      "estimatedMinutes": 60
                                    }
                                  ]
                                }
                                """.formatted(
                                registration.userId(),
                                goalId,
                                System.nanoTime(),
                                taskDate,
                                taskDate.plusDays(7),
                                title,
                                taskDate
                        )))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("tasks").get(0).get("id").asText();
    }

    private String createGoal(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Agent 每日任务操作",
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
                                  "email": "task-tool-%s-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "任务工具用户"
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
