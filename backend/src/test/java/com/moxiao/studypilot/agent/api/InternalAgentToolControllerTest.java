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
                        .value("READ"));
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
