package com.moxiao.studypilot.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InternalAgentToolControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void newUserWithoutRoadmapReceivesContextWarningInsteadOfTransactionRollback() throws Exception {
        String registration = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"context-%d@example.com","password":"Password123!",
                                 "displayName":"新用户"}
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String ownerId = new tools.jackson.databind.ObjectMapper().readTree(registration)
                .get("user").get("id").asText();
        mockMvc.perform(post("/internal/agent-tools/learning.context.get/invoke")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + ownerId + "\",\"arguments\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roadmap").doesNotExist())
                .andExpect(jsonPath("$.data.warnings").isNotEmpty())
                .andExpect(jsonPath("$.data.learning.goals").isArray());
    }

    @Test
    void catalogAndInvocationRequireInternalToken() throws Exception {
        mockMvc.perform(get("/internal/agent-tools/catalog"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/agent-tools/learning.context.get/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"user-1\",\"arguments\":{}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTypedCatalogAndInvokesWithServerOwnedContext() throws Exception {
        mockMvc.perform(get("/internal/agent-tools/catalog")
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'navigation.resolve')].effect")
                        .value("NAVIGATE"))
                .andExpect(jsonPath("$[?(@.name == 'learning.context.get')].effect")
                        .value("READ"))
                .andExpect(jsonPath("$[?(@.name == 'roadmap.node.get')].effect")
                        .value("READ"))
                .andExpect(jsonPath("$[?(@.name == 'assessment.wrong_questions.summary')].effect")
                        .value("READ"))
                .andExpect(jsonPath("$[?(@.name == 'workspaces.list')].effect")
                        .value("READ"))
                // Task 30：目录必须向 Python 暴露封闭输出 Schema 与超时，而不是空的 type:object。
                .andExpect(jsonPath("$[?(@.name == 'learning.context.get')]"
                        + ".outputSchema.additionalProperties").value(false))
                .andExpect(jsonPath("$[?(@.name == 'learning.context.get')]"
                        + ".outputSchema.properties").isNotEmpty())
                .andExpect(jsonPath("$[?(@.name == 'learning.context.get')].timeoutMillis")
                        .value(15000))
                .andExpect(jsonPath("$[?(@.name == 'artifacts.submit')].outputSchema.type")
                        .value("object"))
                .andExpect(jsonPath("$[?(@.name == 'assessment.wrong_questions.list')]"
                        + ".outputSchema.type").value("array"));
        mockMvc.perform(post("/internal/agent-tools/navigation.resolve/invoke")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"user-7","arguments":{"routeKey":"ROADMAP"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolName").value("navigation.resolve"))
                .andExpect(jsonPath("$.data.routeKey").value("ROADMAP"));
    }
}
