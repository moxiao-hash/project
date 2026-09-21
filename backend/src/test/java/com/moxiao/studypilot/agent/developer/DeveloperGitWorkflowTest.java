package com.moxiao.studypilot.agent.developer;

import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.domain.ExecutionType;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.domain.NotificationType;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Autowired private AgentGovernanceService governanceService;
    @Autowired private NotificationService notificationService;

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
        // Task 32：push 确认必须回传预览绑定的全部事实；缺一项 Java 侧就拒绝。
        ObjectNode pushArguments = objectMapper.createObjectNode()
                .put("workspaceId", workspaceId)
                .put("remoteName", pushPreview.remoteName())
                .put("branch", pushPreview.branch())
                .put("expectedHead", pushPreview.expectedHead())
                .put("remoteUrlDigest", pushPreview.remoteUrlDigest())
                .put("expectedRemoteRef", pushPreview.expectedRemoteRef())
                .put("expectedRemoteRefCommit", pushPreview.expectedRemoteRefCommit())
                .put("timeoutSeconds", pushPreview.timeoutSeconds());
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

    @Test
    void prepareIsSideEffectFreeAndRepeatedConfirmationNeverCommitsOrPushesAgain() throws Exception {
        Registration owner = registerUser();
        String workspaceId = registerWorkspace(owner.userId());
        Files.writeString(workspaceRoot.resolve("Example.java"), "class Example { int value = 1; }\n");

        String headBefore = localHead();
        String remoteBefore = remoteHead();
        int notificationsBefore = notificationService.list(owner.userId()).size();
        int auditsBefore = governanceService.listAuditLogs(owner.userId()).size();

        GitCommitPreview commitPreview = developerService.previewGitCommit(
                owner.userId(), workspaceId, List.of("Example.java"), "feat: update example");
        String commitActionId = prepareCommit(owner.userId(), workspaceId, commitPreview);
        assertEquals(headBefore, localHead(), "prepare 不得产生提交");
        assertEquals(remoteBefore, remoteHead(), "prepare 不得产生推送");

        confirm(owner.userId(), commitActionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        String commitId = localHead();
        assertNotEquals(headBefore, commitId);
        assertEquals(remoteBefore, remoteHead(), "确认 commit 不得顺带 push");

        // 重复确认同一次 commit：不得新增提交、通知或审计。
        confirm(owner.userId(), commitActionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.commitId").value(commitId));
        assertEquals(commitId, localHead(), "重复确认 commit 不得产生第二个提交");

        GitPushPreview pushPreview = developerService.previewGitPush(owner.userId(), workspaceId);
        String pushActionId = preparePush(owner.userId(), workspaceId, pushPreview);
        assertEquals(remoteBefore, remoteHead(), "push 预览不得推送");

        confirm(owner.userId(), pushActionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertEquals(commitId, remoteHead());
        int notificationsAfterPush = notificationService.list(owner.userId()).size();
        int auditsAfterPush = governanceService.listAuditLogs(owner.userId()).size();

        // 重复确认同一次 push：远端 ref 不得再次变化，也不得新增通知或审计。
        confirm(owner.userId(), pushActionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertEquals(commitId, remoteHead(), "重复确认 push 不得再次推送");
        assertEquals(notificationsAfterPush, notificationService.list(owner.userId()).size());
        assertEquals(auditsAfterPush, governanceService.listAuditLogs(owner.userId()).size());

        // 两次高风险确认各自留下治理执行、完成通知与状态变更审计。
        var executions = governanceService.listExecutions(owner.userId());
        assertEquals(2, executions.stream()
                .filter(execution -> execution.getStatus() == ExecutionStatus.SUCCEEDED).count(),
                "commit 与 push 必须各自恰好一条 SUCCEEDED 治理执行");
        assertTrue(executions.stream().anyMatch(execution ->
                        execution.getExecutionType() == ExecutionType.GIT_COMMIT
                                && execution.getStatus() == ExecutionStatus.SUCCEEDED),
                "必须存在 GIT_COMMIT 治理执行");
        assertTrue(executions.stream().anyMatch(execution ->
                        execution.getExecutionType() == ExecutionType.GIT_PUSH
                                && execution.getStatus() == ExecutionStatus.SUCCEEDED),
                "必须存在 GIT_PUSH 治理执行");

        var readyNotifications = notificationService.list(owner.userId()).stream()
                .filter(notification -> notification.getType() == NotificationType.AGENT_ACTION_READY)
                .count();
        var completedNotifications = notificationService.list(owner.userId()).stream()
                .filter(notification -> notification.getType() == NotificationType.AGENT_ACTION_COMPLETED)
                .count();
        assertEquals(2, readyNotifications, "commit 与 push 各产生一条待确认通知");
        assertEquals(2, completedNotifications, "commit 与 push 各产生一条完成通知，重复确认不重复通知");

        var auditLogs = governanceService.listAuditLogs(owner.userId());
        assertTrue(auditLogs.size() > auditsBefore, "确认必须留下审计");
        for (var execution : executions) {
            assertTrue(auditLogs.stream().anyMatch(log ->
                            "EXECUTION_STATUS_CHANGED".equals(log.getAction())
                                    && execution.getId().equals(log.getTargetId())),
                    "每个治理执行都必须有状态变更审计");
        }
        assertTrue(auditLogs.stream().anyMatch(log ->
                        "EXECUTION_STATUS_CHANGED".equals(log.getAction())
                                && log.getDetails() != null
                                && log.getDetails().contains("SUCCEEDED")),
                "审计必须记录 SUCCEEDED 终态");
    }

    private String prepareCommit(String ownerId, String workspaceId, GitCommitPreview preview)
            throws Exception {
        ObjectNode arguments = objectMapper.createObjectNode()
                .put("workspaceId", workspaceId)
                .put("message", preview.message())
                .put("expectedHead", preview.expectedHead())
                .put("changeFingerprint", preview.changeFingerprint());
        arguments.putArray("paths").add("Example.java");
        return actionId(prepare(ownerId, "developer.git.commit", arguments));
    }

    private String preparePush(String ownerId, String workspaceId, GitPushPreview preview)
            throws Exception {
        ObjectNode arguments = objectMapper.createObjectNode()
                .put("workspaceId", workspaceId)
                .put("remoteName", preview.remoteName())
                .put("branch", preview.branch())
                .put("expectedHead", preview.expectedHead())
                .put("remoteUrlDigest", preview.remoteUrlDigest())
                .put("expectedRemoteRef", preview.expectedRemoteRef())
                .put("expectedRemoteRefCommit", preview.expectedRemoteRefCommit())
                .put("timeoutSeconds", preview.timeoutSeconds());
        return actionId(prepare(ownerId, "developer.git.push", arguments));
    }

    private String actionId(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        String body = actions.andExpect(status().isOk())
                .andExpect(jsonPath("$.action.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.action.riskLevel").value("HIGH"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("action").path("actionId").asText();
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
