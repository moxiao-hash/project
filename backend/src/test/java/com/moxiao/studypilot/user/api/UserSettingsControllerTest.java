package com.moxiao.studypilot.user.api;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void savesAndReadsPersonalLearningConstraints() throws Exception {
        String token = registerUser();

        mockMvc.perform(put("/api/user-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timeZone": "Asia/Shanghai",
                                  "dailyStudyLimitMinutes": 120,
                                  "weekendPreference": "MORE",
                                  "defaultPrivacyLevel": "NORMAL",
                                  "weeklyAvailability": [
                                    {
                                      "dayOfWeek": "MONDAY",
                                      "startTime": "19:00",
                                      "endTime": "21:00"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyStudyLimitMinutes").value(120))
                .andExpect(jsonPath("$.weeklyAvailability[0].dayOfWeek").value("MONDAY"));

        mockMvc.perform(get("/api/user-settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.weekendPreference").value("MORE"));
    }

    private String registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "settings-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "设置用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.get("accessToken").asText();
    }
}
