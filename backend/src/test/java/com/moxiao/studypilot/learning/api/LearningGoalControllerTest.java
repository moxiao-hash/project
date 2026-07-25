package com.moxiao.studypilot.learning.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LearningGoalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsLearningGoal() throws Exception {
        LocalDate targetDate = LocalDate.now().plusDays(30);

        mockMvc.perform(post("/api/learning-goals")
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
        mockMvc.perform(post("/api/learning-goals")
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
}
