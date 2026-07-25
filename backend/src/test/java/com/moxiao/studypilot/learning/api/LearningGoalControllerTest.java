package com.moxiao.studypilot.learning.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LearningGoalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsLearningGoal() throws Exception {
        LocalDate targetDate = LocalDate.now().plusDays(30);
        String accessToken = registerUser();

        mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "完成 Java + AI 项目",
                                  "targetDate": "%s",
                                  "weeklyStudyHours": 10
                                }
                                """.formatted(targetDate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.title").value("完成 Java + AI 项目"))
                .andExpect(jsonPath("$.targetDate").value(targetDate.toString()))
                .andExpect(jsonPath("$.weeklyStudyHours").value(10))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void rejectsInvalidLearningGoal() throws Exception {
        String accessToken = registerUser();

        mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "   ",
                                  "targetDate": "%s",
                                  "weeklyStudyHours": 0
                                }
                                """.formatted(LocalDate.now())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/learning-goals"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listsOnlyCurrentUsersGoals() throws Exception {
        String ownerToken = registerUser();
        String otherUserToken = registerUser();

        mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "只属于当前用户的目标",
                                  "targetDate": "%s",
                                  "weeklyStudyHours": 8
                                }
                                """.formatted(LocalDate.now().plusDays(20))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/learning-goals")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("只属于当前用户的目标"));

        mockMvc.perform(get("/api/learning-goals")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    private String registerUser() throws Exception {
        String email = "goal-owner-%d@example.com".formatted(System.nanoTime());
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "Password123!",
                                  "displayName": "目标用户"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.get("accessToken").asText();
    }
}
