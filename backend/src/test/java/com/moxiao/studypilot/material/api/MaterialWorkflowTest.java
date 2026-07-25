package com.moxiao.studypilot.material.api;

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
class MaterialWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registersMaterialAndAcceptsAsyncProcessingResult() throws Exception {
        String token = registerUser();

        MvcResult result = mockMvc.perform(post("/api/materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Spring Boot 官方教程",
                                  "materialType": "WEB_PAGE",
                                  "category": "LEARNING_MATERIAL",
                                  "privacyLevel": "NORMAL",
                                  "sourceUrl": "https://spring.io/guides"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.processingStatus").value("PENDING"))
                .andReturn();
        String materialId = readId(result);

        mockMvc.perform(patch("/internal/materials/{id}/processing", materialId)
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "READY",
                                  "summary": "Spring Boot 入门与常用能力",
                                  "tags": ["Spring Boot", "Java"],
                                  "knowledgePoints": ["依赖注入", "自动配置"],
                                  "contentReference": "object://materials/parsed/guide.txt"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("READY"))
                .andExpect(jsonPath("$.knowledgePoints[0]").value("依赖注入"));

        mockMvc.perform(get("/api/materials")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].summary").value("Spring Boot 入门与常用能力"));
    }

    @Test
    void rejectsInvalidInternalServiceToken() throws Exception {
        mockMvc.perform(patch("/internal/materials/missing/processing")
                        .header("X-Internal-Service-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "FAILED"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    private String registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "material-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "资料用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String readId(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.get("id").asText();
    }
}
