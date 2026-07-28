package com.moxiao.studypilot.aicredential.api;

import com.moxiao.studypilot.agent.infrastructure.AuditLogJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "AI_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "studypilot.ai-service-base-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class AiCredentialControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuditLogJpaRepository auditRepository;

    @Test
    void publicApiNeverEchoesSecretAndKeepsUsersIsolated() throws Exception {
        Registration first = register("credential-one@example.com");
        Registration second = register("credential-two@example.com");
        String secret = "sk-this-value-must-never-be-returned";

        mockMvc.perform(put("/api/ai-settings/deepseek-key")
                        .header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new KeyRequest(secret))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deepseek.configured").value(true))
                .andExpect(jsonPath("$.deepseek.source").value("USER"))
                .andExpect(jsonPath("$.deepseek.maskedSuffix").value("rned"))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(result ->
                        assertThat(result.getResponse().getContentAsString())
                                .doesNotContain(secret)
                                .doesNotContain("ciphertext")
                                .doesNotContain("\"iv\""));

        mockMvc.perform(get("/api/ai-settings")
                        .header("Authorization", bearer(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelProvider").value("unknown"))
                .andExpect(jsonPath("$.modelName").value("unknown"))
                .andExpect(jsonPath("$.deepseek.source").value("NONE"))
                .andExpect(result ->
                        assertThat(result.getResponse().getContentAsString()).doesNotContain(secret));

        assertThat(auditRepository.findAllByOwnerIdOrderByCreatedAtDesc(first.userId()))
                .isNotEmpty()
                .allSatisfy(log -> assertThat(log.getDetails()).doesNotContain(secret));
    }

    @Test
    void validatesInputAndRequiresBearerForPublicEndpoints() throws Exception {
        Registration user = register("credential-validation@example.com");
        mockMvc.perform(get("/api/ai-settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/ai-settings/tavily-key")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void internalLookupRequiresTokenAndReturnsOnlyRuntimeKey() throws Exception {
        Registration user = register("credential-internal@example.com");
        String secret = "tvly-runtime-secret";
        mockMvc.perform(put("/api/ai-settings/tavily-key")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new KeyRequest(secret))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/internal/ai-credentials/tavily")
                        .queryParam("ownerId", user.userId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/ai-credentials/tavily")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .queryParam("ownerId", user.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value(secret))
                .andExpect(jsonPath("$.ciphertext").doesNotExist());

        mockMvc.perform(delete("/api/ai-settings/tavily-key")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/internal/ai-credentials/tavily")
                        .header("X-Internal-Service-Token", "test-internal-token")
                        .queryParam("ownerId", user.userId()))
                .andExpect(status().isNotFound());
    }

    private Registration register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"StudyPilot123!","displayName":"Credential"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(body.get("accessToken").asText(), body.get("user").get("id").asText());
    }

    private String bearer(Registration registration) {
        return "Bearer " + registration.token();
    }

    private record KeyRequest(String apiKey) {}
    private record Registration(String token, String userId) {}
}
