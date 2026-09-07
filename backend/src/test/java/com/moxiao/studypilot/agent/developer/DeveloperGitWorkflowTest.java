package com.moxiao.studypilot.agent.developer;

import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceEntity;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceJpaRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DeveloperGitWorkflowTest {
    private static final String INTERNAL_TOKEN = "test-internal-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectWorkspaceJpaRepository workspaceRepository;
    @Autowired private WorkspaceDeveloperService developerService;

    @TempDir Path temporary;
    private Path workspaceRoot;
    private Path remoteRoot;

    @BeforeEach
    void setUp() throws Exception {
        workspaceRoot = Files.createDirectory(temporary.resolve("workspace"));
        remoteRoot = temporary.resolve("remote.git");
        run(workspaceRoot, "git", "init", "-b", "main");
        run(workspaceRoot, "git", "config", "user.name", "StudyPilot");
        run(workspaceRoot, "git", "config", "user.email", "test@example.com");
        run(workspaceRoot, "git", "init", "--bare", remoteRoot.toString());
        run(workspaceRoot, "git", "remote", "add", "origin", remoteRoot.toUri().toString());
        Files.writeString(workspaceRoot.resolve("Example.java"), "class Example {}\n");
        run(workspaceRoot, "git", "add", "Example.java");
        run(workspaceRoot, "git", "commit", "-m", "initial");
        run(workspaceRoot, "git", "push", "-u", "origin", "main");
    }

    @Test
    void commitAndPushAreTwoIndependentHighRiskConfirmations() throws Exception {
        Registration owner = registerUser();
        String workspaceId = registerWorkspace(owner.userId());
        Files.writeString(workspaceRoot.resolve("Example.java"), "class Example { int value = 1; }\n");

        GitCommitPreview commitPreview = developerService.previewGitCommit(
                owner.userId(), workspaceId, List.of("Example.java"), "feat: update example");
        String remoteBefore = remoteHead();
        ObjectNode commitArguments = objectMapper.createObjectNode()
                .put("workspaceId", workspaceId)
                .put("message", commitPreview.message())
                .put("expectedHead", commitPreview.expectedHead())
                .put("changeFingerprint", commitPreview.changeFingerprint());
        commitArguments.putArray("paths").add("Example.java");

        String commitAction = prepare(owner.userId(), "developer.git.commit", commitArguments)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.action.riskLevel").value("HIGH"))
                .andReturn().getResponse().getContentAsString();
        String commitActionId = objectMapper.readTree(commitAction).path("action").path("actionId").asText();
        assertEquals(commitPreview.expectedHead(), localHead());

        MvcResult committed = confirm(owner.userId(), commitActionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andReturn();
        String commitId = objectMapper.readTree(committed.getResponse().getContentAsString())
                .path("result").path("commitId").asText();
        assertNotEquals(commitPreview.expectedHead(), commitId);
        assertEquals(remoteBefore, remoteHead(), "确认 commit 不得顺带执行 push");

        GitPushPreview pushPreview = developerService.previewGitPush(owner.userId(), workspaceId);
        ObjectNode pushArguments = objectMapper.createObjectNode()
                .put("workspaceId", workspaceId)
                .put("remoteName", pushPreview.remoteName())
                .put("branch", pushPreview.branch())
                .put("expectedHead", pushPreview.expectedHead());
        String pushBody = prepare(owner.userId(), "developer.git.push", pushArguments)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.action.riskLevel").value("HIGH"))
                .andReturn().getResponse().getContentAsString();
        String pushActionId = objectMapper.readTree(pushBody).path("action").path("actionId").asText();
        assertNotEquals(commitActionId, pushActionId);
        assertEquals(remoteBefore, remoteHead());

        confirm(owner.userId(), pushActionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertEquals(commitId, remoteHead());
    }

    private org.springframework.test.web.servlet.ResultActions prepare(
            String ownerId, String toolName, JsonNode arguments
    ) throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("ownerId", ownerId)
                .put("idempotencyKey", toolName + ':' + UUID.randomUUID())
                .set("arguments", arguments);
        return mockMvc.perform(post("/internal/agent-tools/{toolName}/invoke", toolName)
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

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"developer-git-%d@example.com",
                                 "password":"Password123!","displayName":"Git 用户"}
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(body.path("user").path("id").asText());
    }

    private String registerWorkspace(String ownerId) throws Exception {
        String id = UUID.randomUUID().toString();
        workspaceRepository.saveAndFlush(new ProjectWorkspaceEntity(
                id, ownerId, "git-workflow", workspaceRoot.toRealPath().toString(),
                "test-hash", Instant.now()));
        return id;
    }

    private String localHead() throws Exception {
        return run(workspaceRoot, "git", "rev-parse", "HEAD").trim();
    }

    private String remoteHead() throws Exception {
        return run(workspaceRoot, "git", "--git-dir", remoteRoot.toString(),
                "rev-parse", "refs/heads/main").trim();
    }

    private static String run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output;
    }

    private record Registration(String userId) { }
}
