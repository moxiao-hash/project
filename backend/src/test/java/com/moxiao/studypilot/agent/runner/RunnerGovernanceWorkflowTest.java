package com.moxiao.studypilot.agent.runner;

import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RunnerGovernanceWorkflowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RunnerGovernanceService runnerService;
    @Autowired private RunnerExecutionJpaRepository runnerRepository;
    @Autowired private AgentExecutionJpaRepository agentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private IsolatedRunnerExecutor isolatedExecutor;

    @TempDir Path tempDir;

    private String ownerId;
    private String token;
    private String workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        reset(isolatedExecutor);
        Auth auth = register("runner");
        ownerId = auth.ownerId();
        token = auth.token();
        workspaceId = createWorkspace(token, "first", tempDir);
        grantRunner(token);
        doAnswer(invocation -> successfulResult(
                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                invocation.getArgument(4)))
                .when(isolatedExecutor).execute(anyString(), anyString(),
                        org.mockito.ArgumentMatchers.any(RunnerTemplateType.class),
                        anyString(), anyList(), anyInt());
    }

    @Test
    void previewIsSideEffectFreeAndUsesPostEndpoint() throws Exception {
        long recordsBeforePreview = runnerRepository.count();
        mockMvc.perform(post("/api/runner/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(workspaceId, "preview-key", "MAVEN_TEST", "SampleTest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(workspaceId))
                .andExpect(jsonPath("$.commandTokens[2]").value("-Dtest=SampleTest"));

        assertThat(runnerRepository.count()).isEqualTo(recordsBeforePreview);
        verify(isolatedExecutor, never()).execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt());
    }

    @Test
    void confirmExecutesExactlyRecordedWorkspaceTemplateAndTokensWithoutWorkspaceSwap() throws Exception {
        Path secondRoot = tempDir.resolve("second");
        java.nio.file.Files.createDirectory(secondRoot);
        String secondWorkspaceId = createWorkspace(token, "second", secondRoot);
        RunnerExecutionResult first = runnerService.submit(ownerId,
                request(workspaceId, "first-prepare", RunnerTemplateType.PREPARE_DEPENDENCIES, null));
        RunnerExecutionResult second = runnerService.submit(ownerId,
                request(secondWorkspaceId, "second-prepare", RunnerTemplateType.PREPARE_DEPENDENCIES, null));

        RunnerExecutionResult confirmed = runnerService.confirm(ownerId, second.executionId());

        assertThat(confirmed.executionId()).isEqualTo(second.executionId());
        assertThat(confirmed.governanceExecutionId()).isNotBlank();
        verify(isolatedExecutor).execute(
                confirmed.governanceExecutionId(), secondWorkspaceId,
                RunnerTemplateType.PREPARE_DEPENDENCIES, secondRoot.toRealPath().toString(),
                List.of("mvn", "dependency:resolve"), 180);
        verify(isolatedExecutor, never()).execute(anyString(),
                org.mockito.ArgumentMatchers.eq(workspaceId),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt());
        assertThat(runnerService.get(ownerId, first.executionId()).status())
                .isEqualTo(ExecutionStatus.WAITING_CONFIRMATION.name());
    }

    @Test
    void repeatedConfirmReturnsStoredTerminalResultAndExecutesOnce() {
        RunnerExecutionResult pending = runnerService.submit(ownerId,
                request(workspaceId, "repeat-confirm", RunnerTemplateType.PREPARE_DEPENDENCIES, null));

        RunnerExecutionResult first = runnerService.confirm(ownerId, pending.executionId());
        RunnerExecutionResult repeated = runnerService.confirm(ownerId, pending.executionId());

        assertThat(repeated).isEqualTo(first);
        verify(isolatedExecutor, times(1)).execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt());
    }

    @Test
    void submitIsIdempotentAndConflictingReuseReturnsConflict() {
        RunnerExecutionRequest request = request(
                workspaceId, "stable-client-key", RunnerTemplateType.MAVEN_TEST, "SampleTest");

        RunnerExecutionResult first = runnerService.submit(ownerId, request);
        RunnerExecutionResult repeated = runnerService.submit(ownerId, request);

        assertThat(repeated).isEqualTo(first);
        verify(isolatedExecutor, times(1)).execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt());
        assertThatThrownBy(() -> runnerService.submit(ownerId,
                request(workspaceId, "stable-client-key", RunnerTemplateType.MAVEN_COMPILE, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void completedExecutionRemainsIdempotentAfterWorkspaceIsMoved() throws Exception {
        Path originalRoot = tempDir.resolve("idempotent-original");
        java.nio.file.Files.createDirectory(originalRoot);
        String movableWorkspaceId = createWorkspace(token, "movable", originalRoot);
        RunnerExecutionRequest request = request(
                movableWorkspaceId, "moved-after-completion", RunnerTemplateType.MAVEN_COMPILE, null);
        RunnerExecutionResult completed = runnerService.submit(ownerId, request);
        Path movedRoot = tempDir.resolve("idempotent-moved");
        java.nio.file.Files.move(originalRoot, movedRoot);

        RunnerExecutionResult repeated = runnerService.submit(ownerId, request);

        assertThat(repeated).isEqualTo(completed);
        verify(isolatedExecutor, times(1)).execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt());
    }

    @Test
    void concurrentSubmitClaimsExecutionOnlyOnce() throws Exception {
        CountDownLatch executorStarted = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        doAnswer(invocation -> {
            executorStarted.countDown();
            assertThat(allowCompletion.await(5, TimeUnit.SECONDS)).isTrue();
            return successfulResult(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(4));
        }).when(isolatedExecutor).execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt());
        RunnerExecutionRequest request = request(
                workspaceId, "concurrent-submit", RunnerTemplateType.MAVEN_COMPILE, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> runnerService.submit(ownerId, request));
            assertThat(executorStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var repeated = executor.submit(() -> runnerService.submit(ownerId, request));
            assertThat(repeated.get(5, TimeUnit.SECONDS).status()).isEqualTo(ExecutionStatus.RUNNING.name());
            allowCompletion.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(ExecutionStatus.SUCCEEDED.name());
        } finally {
            allowCompletion.countDown();
            executor.shutdownNow();
        }
        verify(isolatedExecutor, times(1)).execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt());
    }

    @Test
    void concurrentConfirmClaimsExecutionOnlyOnce() throws Exception {
        RunnerExecutionResult pending = runnerService.submit(ownerId,
                request(workspaceId, "concurrent-confirm", RunnerTemplateType.PREPARE_DEPENDENCIES, null));
        CountDownLatch executorStarted = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        doAnswer(invocation -> {
            executorStarted.countDown();
            assertThat(allowCompletion.await(5, TimeUnit.SECONDS)).isTrue();
            return successfulResult(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(4));
        }).when(isolatedExecutor).execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> runnerService.confirm(ownerId, pending.executionId()));
            assertThat(executorStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var repeated = executor.submit(() -> runnerService.confirm(ownerId, pending.executionId()));
            assertThat(repeated.get(5, TimeUnit.SECONDS).status()).isEqualTo(ExecutionStatus.RUNNING.name());
            allowCompletion.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(ExecutionStatus.SUCCEEDED.name());
        } finally {
            allowCompletion.countDown();
            executor.shutdownNow();
        }
        verify(isolatedExecutor, times(1)).execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt());
    }

    @Test
    void getAndMutationsAreOwnerScoped() throws Exception {
        RunnerExecutionResult pending = runnerService.submit(ownerId,
                request(workspaceId, "owner-scope", RunnerTemplateType.PREPARE_DEPENDENCIES, null));
        Auth other = register("other-runner");

        mockMvc.perform(get("/api/runner/executions/{runnerExecutionId}", pending.executionId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(pending.executionId()))
                .andExpect(jsonPath("$.governanceExecutionId").value(pending.governanceExecutionId()));
        mockMvc.perform(get("/api/runner/executions/{runnerExecutionId}", pending.executionId())
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> runnerService.confirm(other.ownerId(), pending.executionId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> runnerService.reject(other.ownerId(), pending.executionId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void changedWorkspaceBindingConflictsBeforeConfirmation() throws Exception {
        RunnerExecutionResult pending = runnerService.submit(ownerId,
                request(workspaceId, "changed-workspace", RunnerTemplateType.PREPARE_DEPENDENCIES, null));
        Path changed = tempDir.resolve("changed");
        java.nio.file.Files.createDirectory(changed);
        jdbcTemplate.update("update project_workspaces set root_path = ?, root_path_hash = ? where id = ?",
                changed.toRealPath().toString(), "changed-fingerprint", workspaceId);

        assertThatThrownBy(() -> runnerService.confirm(ownerId, pending.executionId()))
                .isInstanceOf(ConflictException.class);
        verify(isolatedExecutor, never()).execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt());
    }

    @Test
    void replacingDirectoryAtSamePathInvalidatesPendingConfirmation() throws Exception {
        Path replaceableRoot = tempDir.resolve("replaceable");
        java.nio.file.Files.createDirectory(replaceableRoot);
        String replaceableWorkspaceId = createWorkspace(token, "replaceable", replaceableRoot);
        RunnerExecutionResult pending = runnerService.submit(ownerId,
                request(replaceableWorkspaceId, "replace-directory", RunnerTemplateType.PREPARE_DEPENDENCIES, null));
        java.nio.file.Files.delete(replaceableRoot);
        java.nio.file.Files.createDirectory(replaceableRoot);

        assertThatThrownBy(() -> runnerService.confirm(ownerId, pending.executionId()))
                .isInstanceOf(ConflictException.class);
        verify(isolatedExecutor, never()).execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt());
    }

    @Test
    void lowRiskExecutionUsesExactRecordedCommand() throws Exception {
        RunnerExecutionResult result = runnerService.submit(ownerId,
                request(workspaceId, "low-risk-exact", RunnerTemplateType.MAVEN_TEST, "SampleTest"));

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED.name());
        verify(isolatedExecutor).execute(
                result.governanceExecutionId(), workspaceId, RunnerTemplateType.MAVEN_TEST,
                tempDir.toRealPath().toString(), List.of("mvn", "test", "-Dtest=SampleTest"), 60);
    }

    @Test
    void executorExceptionFailsRunnerAndGovernanceRecordsConsistently() {
        when(isolatedExecutor.execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("executor unavailable"));

        RunnerExecutionResult result = runnerService.submit(ownerId,
                request(workspaceId, "executor-failure", RunnerTemplateType.MAVEN_COMPILE, null));

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED.name());
        assertThat(result.stderrSummary()).contains("executor unavailable");
        RunnerExecutionEntity persisted = runnerRepository.findById(result.executionId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(agentRepository.findById(result.governanceExecutionId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void rejectOnlyChangesExactRecordAndIsIdempotent() {
        RunnerExecutionResult first = runnerService.submit(ownerId,
                request(workspaceId, "reject-first", RunnerTemplateType.PREPARE_DEPENDENCIES, null));
        RunnerExecutionResult second = runnerService.submit(ownerId,
                request(workspaceId, "reject-second", RunnerTemplateType.PREPARE_DEPENDENCIES, null));

        RunnerExecutionResult rejected = runnerService.reject(ownerId, second.executionId());
        RunnerExecutionResult repeated = runnerService.reject(ownerId, second.executionId());

        assertThat(repeated).isEqualTo(rejected);
        assertThat(runnerService.get(ownerId, first.executionId()).status())
                .isEqualTo(ExecutionStatus.WAITING_CONFIRMATION.name());
        verify(isolatedExecutor, never()).execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString(), anyList(), anyInt());
    }

    private RunnerExecutionRequest request(
            String workspace, String key, RunnerTemplateType template, String targetPattern
    ) {
        return new RunnerExecutionRequest(workspace, template, targetPattern, "runner test", key);
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s-%d@example.com","password":"Password123!","displayName":"Runner"}
                                """.formatted(prefix, System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Auth(body.get("user").get("id").asText(), body.get("accessToken").asText());
    }

    private String createWorkspace(String bearerToken, String name, Path root) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", "Bearer " + bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","rootPath":"%s"}
                                """.formatted(name, root.toRealPath())))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void grantRunner(String bearerToken) throws Exception {
        mockMvc.perform(post("/api/agent-grants")
                        .header("Authorization", "Bearer " + bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopes":["RUNNER_MANAGEMENT"],"expiresAt":"2099-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isCreated());
    }

    private String requestJson(String workspace, String key, String template, String targetPattern) {
        return """
                {"workspaceId":"%s","templateType":"%s","targetPattern":"%s",\
                 "explanation":"runner test","idempotencyKey":"%s"}
                """.formatted(workspace, template, targetPattern, key);
    }

    private record Auth(String ownerId, String token) { }
}
