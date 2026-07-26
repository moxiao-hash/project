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
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InternalAdaptationContextContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aggregatesBalancedDeviationSignalsFromRealTaskFacts() throws Exception {
        LocalDate analysisDate = LocalDate.now();
        Registration owner = registerUser();
        List<String> taskIds = createConfirmedPlan(owner, analysisDate);

        complete(owner.userId(), taskIds.get(0), 1, 90);
        complete(owner.userId(), taskIds.get(1), 1, 90);
        complete(owner.userId(), taskIds.get(2), 1, 90);
        skip(owner.userId(), taskIds.get(3), 1);
        skip(owner.userId(), taskIds.get(4), 1);

        mockMvc.perform(get(
                                "/internal/users/{ownerId}/adaptation-context",
                                owner.userId()
                        )
                        .queryParam("analysisDate", analysisDate.toString())
                        .queryParam("windowDays", "14")
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(owner.userId()))
                .andExpect(jsonPath("$.analysisDate").value(analysisDate.toString()))
                .andExpect(jsonPath("$.windowDays").value(14))
                .andExpect(jsonPath("$.plan.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.tasks.length()").value(6))
                .andExpect(jsonPath(
                        "$.signals[?(@.type == 'OVERDUE_TASKS')].count"
                ).value(1))
                .andExpect(jsonPath(
                        "$.signals[?(@.type == 'CONSECUTIVE_SKIPS')].count"
                ).value(2))
                .andExpect(jsonPath(
                        "$.signals[?(@.type == 'TIME_ESTIMATE_BIAS')].deviationRatio"
                ).value(0.5));
    }

    @Test
    void adaptationContextRequiresInternalTokenAndValidWindow() throws Exception {
        Registration owner = registerUser();

        mockMvc.perform(get(
                                "/internal/users/{ownerId}/adaptation-context",
                                owner.userId()
                        )
                        .queryParam("analysisDate", LocalDate.now().toString())
                        .queryParam("windowDays", "14"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(
                                "/internal/users/{ownerId}/adaptation-context",
                                owner.userId()
                        )
                        .queryParam("analysisDate", LocalDate.now().toString())
                        .queryParam("windowDays", "0")
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isBadRequest());
    }

    private List<String> createConfirmedPlan(
            Registration owner,
            LocalDate analysisDate
    ) throws Exception {
        String goalId = createGoal(owner.token());
        List<LocalDate> dates = List.of(
                analysisDate.minusDays(7),
                analysisDate.minusDays(6),
                analysisDate.minusDays(5),
                analysisDate.minusDays(2),
                analysisDate.minusDays(1),
                analysisDate.minusDays(3)
        );
        StringBuilder tasks = new StringBuilder();
        for (int index = 0; index < dates.size(); index++) {
            if (index > 0) {
                tasks.append(",");
            }
            tasks.append("""
                    {
                      "title": "自适应测试任务 %d",
                      "scheduledDate": "%s",
                      "estimatedMinutes": 60
                    }
                    """.formatted(index + 1, dates.get(index)));
        }
        MvcResult result = mockMvc.perform(post("/internal/confirmed-learning-plans")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "goalId": "%s",
                                  "idempotencyKey": "adaptation-context-%d",
                                  "title": "自适应上下文测试计划",
                                  "startDate": "%s",
                                  "endDate": "%s",
                                  "tasks": [%s]
                                }
                                """.formatted(
                                owner.userId(),
                                goalId,
                                System.nanoTime(),
                                analysisDate.minusDays(7),
                                analysisDate.plusDays(7),
                                tasks
                        )))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode taskNodes = objectMapper.readTree(
                result.getResponse().getContentAsString()
        ).get("tasks");
        List<String> ids = new ArrayList<>();
        taskNodes.forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }

    private void complete(
            String ownerId,
            String taskId,
            int version,
            int actualMinutes
    ) throws Exception {
        change(ownerId, taskId, version, """
                "status": "COMPLETED",
                "actualMinutes": %d,
                "reason": "记录实际时长"
                """.formatted(actualMinutes));
    }

    private void skip(String ownerId, String taskId, int version) throws Exception {
        change(ownerId, taskId, version, """
                "status": "SKIPPED",
                "reason": "连续跳过测试"
                """);
    }

    private void change(
            String ownerId,
            String taskId,
            int version,
            String fields
    ) throws Exception {
        mockMvc.perform(patch("/internal/learning-tasks/{taskId}/status", taskId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "idempotencyKey": "adaptation-change-%s-%d",
                                  "expectedVersion": %d,
                                  %s
                                }
                                """.formatted(
                                ownerId,
                                taskId,
                                System.nanoTime(),
                                version,
                                fields
                        )))
                .andExpect(status().isOk());
    }

    private String createGoal(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "自适应计划目标",
                                  "targetDate": "%s",
                                  "weeklyStudyHours": 10
                                }
                                """.formatted(LocalDate.now().plusMonths(3))))
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
                                  "email": "adaptation-context-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "自适应测试用户"
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
