package com.moxiao.studypilot.agent.runner;

import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.tool.AgentToolRiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runner 必须在"登记工作区 + 已验证相对子目录"里执行，而不是永远在工作区根目录。
 *
 * <p>工作目录与工作区路径一样属于安全边界：绝对路径、目录穿越、符号链接、缺失目录、
 * 指向工作区外部的路径，都必须在真正执行之前被拒绝。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunnerWorkingDirectoryWorkflowTest {

    private static final String OUTSIDE_MARKER = "outside-workspace";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RunnerGovernanceService runnerService;


    @MockitoBean private IsolatedRunnerExecutor isolatedExecutor;

    @TempDir Path tempDir;

    private String ownerId;
    private String token;
    private String workspaceId;
    private Path workspaceRoot;

    @BeforeEach
    void setUp() throws Exception {
        reset(isolatedExecutor);
        workspaceRoot = Files.createDirectory(tempDir.resolve("workspace"));
        Files.createDirectories(workspaceRoot.resolve("backend"));
        Files.createDirectories(workspaceRoot.resolve("web"));
        Files.writeString(workspaceRoot.resolve("backend/pom.xml"), "<project/>");
        Files.writeString(workspaceRoot.resolve("web/package.json"), "{}");
        Files.createDirectory(tempDir.resolve(OUTSIDE_MARKER));
        Files.createSymbolicLink(workspaceRoot.resolve("escape"), tempDir.resolve(OUTSIDE_MARKER));

        Auth auth = register("runner-wd");
        ownerId = auth.ownerId();
        token = auth.token();
        workspaceId = createWorkspace(token, workspaceRoot);
        grantRunner(token);
        doAnswer(invocation -> successfulResult(invocation.getArgument(0), invocation.getArgument(2),
                invocation.getArgument(3), invocation.getArgument(8)))
                .when(isolatedExecutor).execute(anyString(), anyString(), anyString(),
                        any(RunnerTemplateType.class), any(), any(), anyString(), anyString(),
                        anyList(), anyInt());
    }

    @Test
    void previewCarriesTheValidatedRelativeWorkingDirectory() {
        RunnerExecutionPreview preview = runnerService.preview(
                ownerId, request(workspaceId, "wd-preview", "backend"));

        assertThat(preview.workingDirectory()).isEqualTo("backend");
        assertThat(preview.templateType()).isEqualTo(RunnerTemplateType.PREPARE_DEPENDENCIES);
    }

    @Test
    void blankWorkingDirectoryDefaultsToTheWorkspaceRoot() {
        assertThat(runnerService.preview(ownerId, request(workspaceId, "wd-root", null))
                .workingDirectory()).isEqualTo(".");
        assertThat(runnerService.preview(ownerId, request(workspaceId, "wd-root-blank", "  "))
                .workingDirectory()).isEqualTo(".");
    }

    @Test
    void submitAndConfirmExecuteInsideTheValidatedSubdirectory() throws Exception {
        RunnerExecutionResult submitted = runnerService.submit(
                ownerId, request(workspaceId, "wd-run", "backend"));
        RunnerExecutionResult confirmed = runnerService.confirm(ownerId, submitted.executionId());

        assertThat(confirmed.status()).isEqualTo(ExecutionStatus.SUCCEEDED.name());
        verify(isolatedExecutor).execute(
                eq(confirmed.governanceExecutionId()), eq(ownerId), eq(workspaceId),
                eq(RunnerTemplateType.PREPARE_DEPENDENCIES), any(), any(Instant.class),
                eq(workspaceRoot.toRealPath().toString()), eq("backend"),
                eq(List.of("mvn", "dependency:go-offline")), eq(180));
    }

    @Test
    void rejectsUnsafeWorkingDirectoriesBeforeAnyExecution() {
        List<String> hostile = List.of(
                "/etc", "..", "../outside", "backend/../../outside", "escape", "missing-directory");

        for (String value : hostile) {
            assertThatThrownBy(() -> runnerService.preview(ownerId, request(workspaceId, "wd-" + value, value)))
                    .as("工作目录 %s 必须在执行前被拒绝", value)
                    .isInstanceOf(RuntimeException.class);
        }
        verify(isolatedExecutor, never()).execute(anyString(), anyString(), anyString(),
                any(), any(), any(), anyString(), anyString(), anyList(), anyInt());
    }

    @Test
    void workingDirectorySwappedToSymlinkBeforeConfirmFailsClosed() throws Exception {
        RunnerExecutionResult submitted = runnerService.submit(
                ownerId, request(workspaceId, "wd-swap", "backend"));
        Files.move(workspaceRoot.resolve("backend"), workspaceRoot.resolve("backend-real"));
        Files.createSymbolicLink(workspaceRoot.resolve("backend"), tempDir.resolve(OUTSIDE_MARKER));

        assertThatThrownBy(() -> runnerService.confirm(ownerId, submitted.executionId()))
                .isInstanceOf(RuntimeException.class);
        verify(isolatedExecutor, never()).execute(anyString(), anyString(), anyString(),
                any(), any(), any(), anyString(), anyString(), anyList(), anyInt());
    }

    @Test
    void signedEnvelopeBindsTheWorkingDirectory() {
        RunnerSecurityService runnerSecurityService = new RunnerSecurityService(
                "runner-test-secret-that-is-at-least-32-bytes");
        RunnerSignedEnvelope envelope = runnerSecurityService.createEnvelope(
                "exec-wd", ownerId, workspaceId, RunnerTemplateType.MAVEN_TEST,
                AgentToolRiskLevel.LOW, workspaceRoot.toString(), "backend",
                List.of("mvn", "test"), RunnerIsolationMode.LOCAL_RUNNER,
                true, "512m", "1.0", 60, null);

        assertThat(runnerSecurityService.verifyEnvelope(envelope, Instant.now())).isTrue();

        // 改动工作目录后签名必须失效：工作目录是安全边界的一部分。
        RunnerSignedEnvelope tampered = new RunnerSignedEnvelope(
                envelope.protocolVersion(), envelope.executionId(), envelope.ownerId(),
                envelope.workspaceId(), envelope.templateType(), envelope.riskLevel(),
                envelope.workspacePath(), ".", envelope.commandTokens(), envelope.isolationMode(),
                envelope.networkDisabled(), envelope.memoryLimit(), envelope.cpuLimit(),
                envelope.timeoutSeconds(), envelope.issuedAt(), envelope.confirmedAt(),
                envelope.expiresAt(), envelope.nonce(), envelope.signature());
        assertThat(runnerSecurityService.verifyEnvelope(tampered, Instant.now())).isFalse();
    }

    private RunnerExecutionRequest request(String workspace, String key, String workingDirectory) {
        return new RunnerExecutionRequest(workspace, RunnerTemplateType.PREPARE_DEPENDENCIES,
                null, "runner working directory", key, workingDirectory);
    }

    private RunnerExecutionResult successfulResult(
            String governanceExecutionId, String workspace, RunnerTemplateType template, List<String> tokens
    ) {
        return new RunnerExecutionResult(
                governanceExecutionId, null, workspace, template, ExecutionStatus.SUCCEEDED.name(),
                0, tokens, "ok", "", true, 5L, Instant.parse("2026-09-05T00:00:00Z"));
    }

    private Auth register(String prefix) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s-%d@example.com","password":"Password123!","displayName":"Runner"}
                                """.formatted(prefix, System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Auth(body.get("user").get("id").asText(), body.get("accessToken").asText());
    }

    private String createWorkspace(String bearerToken, Path root) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", "Bearer " + bearerToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"runner-wd","rootPath":"%s"}
                                """.formatted(root.toRealPath())))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void grantRunner(String bearerToken) throws Exception {
        mockMvc.perform(post("/api/agent-grants")
                        .header("Authorization", "Bearer " + bearerToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopes":["RUNNER_MANAGEMENT"],"expiresAt":"2099-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isCreated());
    }

    private record Auth(String ownerId, String token) { }
}
