package com.moxiao.studypilot.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AssistantHealthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void healthSummaryStartsEmptyAndRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/assistant/health"))
                .andExpect(status().isUnauthorized());

        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"agent-health-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"健康指标用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        String token = objectMapper.readTree(registered.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get("/api/assistant/health")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExecutions").value(0))
                .andExpect(jsonPath("$.successfulExecutions").value(0))
                .andExpect(jsonPath("$.failedExecutions").value(0))
                .andExpect(jsonPath("$.successRate").value(0.0))
                .andExpect(jsonPath("$.promptTokens").value(0))
                .andExpect(jsonPath("$.completionTokens").value(0))
                .andExpect(jsonPath("$.estimatedCost").value(0))
                .andExpect(jsonPath("$.costSamples").value(0))
                .andExpect(jsonPath("$.tokenSamples").value(0))
                .andExpect(jsonPath("$.latencySamples").value(0))
                .andExpect(jsonPath("$.pendingConfirmations").value(0));
    }
}
