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
class LearningPlanWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void confirmsPlanCreatesDailyTaskAndRecordsTaskChange() throws Exception {
        String token = registerUser();
        String goalId = createGoal(token);
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        MvcResult planResult = mockMvc.perform(post("/api/learning-plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "goalId": "%s",
                                  "title": "Java + AI 第一阶段",
                                  "startDate": "%s",
                                  "endDate": "%s"
                                }
                                """.formatted(goalId, tomorrow, tomorrow.plusDays(30))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String planId = readId(planResult);

        mockMvc.perform(post("/api/learning-plans/{id}/confirm", planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(get("/api/learning-plans/{id}/versions", planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(1));

        MvcResult taskResult = mockMvc.perform(post("/api/learning-plans/{id}/tasks", planId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "完成 Spring Boot 登录接口",
                                  "scheduledDate": "%s",
                                  "estimatedMinutes": 90
                                }
                                """.formatted(tomorrow)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andReturn();
        String taskId = readId(taskResult);

        mockMvc.perform(get("/api/learning-tasks")
                        .header("Authorization", "Bearer " + token)
                        .param("date", tomorrow.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(taskId));

        mockMvc.perform(patch("/api/learning-tasks/{id}/status", taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "COMPLETED", "reason": "已完成并通过测试"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        mockMvc.perform(get("/api/learning-tasks/{id}/history", taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fromStatus").value("TODO"))
                .andExpect(jsonPath("$[0].toStatus").value("COMPLETED"));

        MvcResult deferredTask = mockMvc.perform(post("/api/learning-plans/{id}/tasks", planId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "阅读 Spring Security 文档",
                                  "scheduledDate": "%s",
                                  "estimatedMinutes": 45
                                }
                                """.formatted(tomorrow)))
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(patch("/api/learning-tasks/{id}/status", readId(deferredTask))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "DEFERRED",
                                  "scheduledDate": "%s",
                                  "reason": "今天时间不足"
                                }
                                """.formatted(tomorrow.plusDays(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEFERRED"))
                .andExpect(jsonPath("$.scheduledDate").value(tomorrow.plusDays(2).toString()));
    }

    @Test
    void preventsAnotherUserFromUsingGoal() throws Exception {
        String ownerToken = registerUser();
        String otherToken = registerUser();
        String goalId = createGoal(ownerToken);

        mockMvc.perform(post("/api/learning-plans")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "goalId": "%s",
                                  "title": "越权计划",
                                  "startDate": "%s",
                                  "endDate": "%s"
                                }
                                """.formatted(
                                        goalId,
                                        LocalDate.now().plusDays(1),
                                        LocalDate.now().plusDays(10)
                                )))
                .andExpect(status().isNotFound());
    }

    private String createGoal(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "完成 Java + AI 项目",
                                  "targetDate": "%s",
                                  "weeklyStudyHours": 10
                                }
                                """.formatted(LocalDate.now().plusMonths(4))))
                .andExpect(status().isCreated())
                .andReturn();
        return readId(result);
    }

    private String registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "plan-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "计划用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.get("accessToken").asText();
    }

    private String readId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
