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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "studypilot.material.storage-root=target/test-material-storage")
@AutoConfigureMockMvc
class WebSearchSourceContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void recordsSearchSourcesAndImportsOnlyAfterOwnerConfirmation() throws Exception {
        Auth owner = registerUser();
        MvcResult recorded = mockMvc.perform(post("/internal/web-searches")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "query": "Spring Boot Java version",
                                  "providerRequestId": "tavily-request-1",
                                  "results": [
                                    {
                                      "title": "Spring Boot Reference",
                                      "url": "https://docs.spring.io/spring-boot/",
                                      "snippet": "System requirements",
                                      "score": 0.95
                                    }
                                  ]
                                }
                                """.formatted(owner.userId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.results[0].importedMaterialId").isEmpty())
                .andReturn();
        String resultId = objectMapper.readTree(recorded.getResponse().getContentAsString())
                .get("results").get(0).get("id").asText();

        MvcResult firstImport = mockMvc.perform(post(
                                "/api/web-search-results/{id}/import",
                                resultId
                        )
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category": "REFERENCE",
                                  "privacyLevel": "NORMAL"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.materialType").value("WEB_PAGE"))
                .andExpect(jsonPath("$.processingStatus").value("PENDING"))
                .andReturn();
        String materialId = objectMapper.readTree(
                firstImport.getResponse().getContentAsString()
        ).get("id").asText();

        mockMvc.perform(post("/api/web-search-results/{id}/import", resultId)
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category": "REFERENCE",
                                  "privacyLevel": "NORMAL"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(materialId));

        Auth other = registerUser();
        mockMvc.perform(post("/api/web-search-results/{id}/import", resultId)
                        .header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category": "REFERENCE",
                                  "privacyLevel": "NORMAL"
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    private Auth registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "web-source-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "搜索资料用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Auth(
                response.get("accessToken").asText(),
                response.get("user").get("id").asText()
        );
    }

    private record Auth(String token, String userId) {
    }
}
