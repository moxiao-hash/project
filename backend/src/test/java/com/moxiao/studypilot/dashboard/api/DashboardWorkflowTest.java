package com.moxiao.studypilot.dashboard.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DashboardWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void dashboardSummarizesUnreadNotifications() throws Exception {
        Registration registration = registerUser();

        MvcResult notification = mockMvc.perform(post("/internal/notifications")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "type": "PLAN_ADJUSTED",
                                  "title": "学习计划已微调",
                                  "content": "一个任务已顺延至周末"
                                }
                                """.formatted(registration.userId())))
                .andExpect(status().isCreated())
                .andReturn();
        String notificationId = readId(notification);

        mockMvc.perform(get("/api/dashboard")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayTaskCount").value(0))
                .andExpect(jsonPath("$.unreadNotificationCount").value(1));

        mockMvc.perform(patch("/api/notifications/{id}/read", notificationId)
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(get("/api/dashboard")
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadNotificationCount").value(0));
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dashboard-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "工作台用户"
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
