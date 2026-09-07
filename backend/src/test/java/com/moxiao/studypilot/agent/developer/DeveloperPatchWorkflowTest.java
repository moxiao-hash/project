package com.moxiao.studypilot.agent.developer;

import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceEntity;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DeveloperPatchWorkflowTest {
    private static final String INTERNAL_TOKEN = "test-internal-token";
    private static final String ORIGINAL = "class Example {\n}\n";
    private static final String CHANGED = "class Example {\n    int value = 1;\n}\n";
    private static final String DIFF = """
            --- a/Example.java
            +++ b/Example.java
            @@ -1,2 +1,3 @@
             class Example {
            +    int value = 1;
             }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectWorkspaceJpaRepository workspaceRepository;

    @TempDir
    Path workspaceRoot;

    @Test
    void highRiskPatchChangesTheFileOnlyAfterDedicatedConfirmation() throws Exception {
        Registration owner = registerUser();
        Workspace workspace = createWorkspace(owner.userId());
        Path source = workspaceRoot.resolve("Example.java");
        Files.writeString(source, ORIGINAL);
        String expectedSha256 = preview(owner.token(), workspace.id());

        MvcResult prepared = invokePatch(owner.userId(), workspace.id(), expectedSha256)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.action.riskLevel").value("HIGH"))
                .andReturn();
        String actionId = objectMapper.readTree(prepared.getResponse().getContentAsString())
                .path("action").path("actionId").asText();
        assertEquals(ORIGINAL, Files.readString(source));

        confirm(owner.userId(), actionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.applied").value(true));
        assertEquals(CHANGED, Files.readString(source));

        confirm(owner.userId(), actionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertEquals(CHANGED, Files.readString(source));
    }

    @Test
    void stalePreviewFailsWithoutOverwritingTheUsersNewContent() throws Exception {
        Registration owner = registerUser();
        Workspace workspace = createWorkspace(owner.userId());
        Path source = workspaceRoot.resolve("Example.java");
        Files.writeString(source, ORIGINAL);
        String expectedSha256 = preview(owner.token(), workspace.id());
        MvcResult prepared = invokePatch(owner.userId(), workspace.id(), expectedSha256)
                .andExpect(status().isOk()).andReturn();
        String actionId = objectMapper.readTree(prepared.getResponse().getContentAsString())
                .path("action").path("actionId").asText();

        String userChange = "class Example {\n    int userValue = 2;\n}\n";
        Files.writeString(source, userChange);

        confirm(owner.userId(), actionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value("目标文件已变化，请重新生成补丁预览"));
        assertEquals(userChange, Files.readString(source));
    }

    @Test
    void rejectsSensitivePatchBeforeCreatingAConfirmationAction() throws Exception {
        Registration owner = registerUser();
        Workspace workspace = createWorkspace(owner.userId());
        Files.writeString(workspaceRoot.resolve("Example.java"), ORIGINAL);
        String expectedSha256 = preview(owner.token(), workspace.id());
        String sensitiveDiff = """
                --- a/Example.java
                +++ b/Example.java
                @@ -1,2 +1,3 @@
                 class Example {
                +    String apiKey = "sk-1234567890abcdefghijklmnop";
                 }
                """;

        invokePatch(owner.userId(), workspace.id(), expectedSha256, sensitiveDiff)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("补丁疑似包含凭据，禁止进入执行流程"));
        assertEquals(ORIGINAL, Files.readString(workspaceRoot.resolve("Example.java")));
    }

    private String preview(String token, String workspaceId) throws Exception {
        MvcResult result = mockMvc.perform(post(
                        "/api/developer/workspaces/{workspaceId}/patch-preview", workspaceId)
                        .param("targetFile", "Example.java")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(DIFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safeToApply").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("expectedSha256").asText();
    }

    private org.springframework.test.web.servlet.ResultActions invokePatch(
            String ownerId, String workspaceId, String expectedSha256
    ) throws Exception {
        return invokePatch(ownerId, workspaceId, expectedSha256, DIFF);
    }

    private org.springframework.test.web.servlet.ResultActions invokePatch(
            String ownerId, String workspaceId, String expectedSha256, String diff
    ) throws Exception {
        ObjectNode arguments = objectMapper.createObjectNode()
                .put("workspaceId", workspaceId)
                .put("targetFile", "Example.java")
                .put("unifiedDiff", diff)
                .put("expectedSha256", expectedSha256)
                .put("explanation", "增加示例字段");
        ObjectNode body = objectMapper.createObjectNode()
                .put("ownerId", ownerId)
                .put("idempotencyKey", "developer-patch:" + UUID.randomUUID())
                .set("arguments", arguments);
        return mockMvc.perform(post("/internal/agent-tools/developer.patch.apply/invoke")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(
            String ownerId, String actionId
    ) throws Exception {
        return mockMvc.perform(post("/internal/agent-tool-actions/{id}/confirm", actionId)
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerId\":\"" + ownerId + "\"}"));
    }

    private Workspace createWorkspace(String ownerId) throws Exception {
        String id = UUID.randomUUID().toString();
        workspaceRepository.saveAndFlush(new ProjectWorkspaceEntity(
                id, ownerId, "developer-test", workspaceRoot.toRealPath().toString(),
                "test-hash", Instant.now()));
        return new Workspace(id);
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"developer-patch-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"Developer Agent 用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(body.path("user").path("id").asText(),
                body.path("accessToken").asText());
    }

    private record Registration(String userId, String token) { }

    private record Workspace(String id) { }
}
