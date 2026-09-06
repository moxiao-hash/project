package com.moxiao.studypilot.agent.runner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.moxiao.studypilot.agent.tool.AgentToolRiskLevel;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RunnerProtocolSecurityTest {

    private RunnerSecurityService securityService;
    private IsolatedRunnerExecutor runnerExecutor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        securityService = new RunnerSecurityService("test-secret-32-bytes-long-123456");
        runnerExecutor = new IsolatedRunnerExecutor(
                securityService,
                (envelope, workspaceId, templateType) -> {
                    throw new IllegalStateException("local runner unavailable");
                }
        );
    }

    @Test
    void shouldSignAndVerifyValidEnvelope() {
        RunnerSignedEnvelope envelope = securityService.createEnvelope(
                "exec-001", "owner-1", "workspace-1", RunnerTemplateType.MAVEN_TEST,
                AgentToolRiskLevel.LOW,
                tempDir.toAbsolutePath().toString(),
                List.of("echo", "hello"),
                RunnerIsolationMode.LOCAL_RUNNER,
                true,
                "512m",
                "1.0",
                60, null
        );

        assertNotNull(envelope);
        assertNotNull(envelope.signature());
        assertNotNull(envelope.nonce());
        assertTrue(envelope.expiresAt().isAfter(Instant.now()));
        assertTrue(securityService.verifyEnvelope(envelope, Instant.now()));
    }

    @Test
    void shouldRejectExpiredEnvelope() {
        RunnerSignedEnvelope envelope = new RunnerSignedEnvelope(
                "v1", "exec-exp", "owner-exp", "workspace-exp",
                RunnerTemplateType.MAVEN_TEST, AgentToolRiskLevel.LOW,
                tempDir.toAbsolutePath().toString(),
                List.of("echo", "test"),
                RunnerIsolationMode.LOCAL_RUNNER,
                true,
                "512m",
                "1.0",
                60, Instant.now().minus(11, ChronoUnit.MINUTES), null,
                Instant.now().minus(1, ChronoUnit.HOURS),
                "test-nonce-1",
                "invalid-sig"
        );

        assertFalse(securityService.verifyEnvelope(envelope, Instant.now()));
    }

    @Test
    void shouldRejectTamperedEnvelope() {
        RunnerSignedEnvelope original = securityService.createEnvelope(
                "exec-002", "owner-2", "workspace-2", RunnerTemplateType.MAVEN_TEST,
                AgentToolRiskLevel.LOW,
                tempDir.toAbsolutePath().toString(),
                List.of("echo", "safe"),
                RunnerIsolationMode.LOCAL_RUNNER,
                true,
                "512m",
                "1.0",
                60, null
        );

        // Tamper command
        RunnerSignedEnvelope tampered = new RunnerSignedEnvelope(
                original.protocolVersion(), original.executionId(), original.ownerId(),
                original.workspaceId(), original.templateType(), original.riskLevel(),
                original.workspacePath(),
                List.of("rm", "-rf", "/"),
                original.isolationMode(),
                original.networkDisabled(),
                original.memoryLimit(),
                original.cpuLimit(),
                original.timeoutSeconds(), original.issuedAt(), original.confirmedAt(),
                original.expiresAt(),
                original.nonce(),
                original.signature()
        );

        assertFalse(securityService.verifyEnvelope(tampered, Instant.now()));
    }

    @Test
    void shouldRejectNonceReplayAttack() {
        RunnerSignedEnvelope envelope = securityService.createEnvelope(
                "exec-003", "owner-3", "workspace-3", RunnerTemplateType.MAVEN_TEST,
                AgentToolRiskLevel.LOW,
                tempDir.toAbsolutePath().toString(),
                List.of("echo", "replay"),
                RunnerIsolationMode.LOCAL_RUNNER,
                true,
                "512m",
                "1.0",
                60, null
        );

        // First verification should pass and consume nonce
        assertTrue(securityService.verifyEnvelope(envelope, Instant.now()));

        // Replay of identical envelope with same nonce should fail
        assertFalse(securityService.verifyEnvelope(envelope, Instant.now()));
    }

    @Test
    void shouldRefuseToSignWithDocumentedDefaultSecret() {
        RunnerSecurityService unsafe = new RunnerSecurityService(
                "studypilot-runner-default-secret-key-32b");

        assertThrows(IllegalStateException.class, () -> unsafe.createEnvelope(
                "exec-default-secret", "owner", "workspace", RunnerTemplateType.MAVEN_TEST,
                AgentToolRiskLevel.LOW, tempDir.toAbsolutePath().toString(),
                List.of("mvn", "test"), RunnerIsolationMode.LOCAL_RUNNER,
                true, "512m", "1.0", 60, null));
    }

    @Test
    void shouldRejectSymlinkEscape() throws Exception {
        Path realDir = tempDir.resolve("real-workspace");
        Files.createDirectories(realDir);

        Path linkDir = tempDir.resolve("symlink-workspace");
        Files.createSymbolicLink(linkDir, realDir);

        assertThrows(SecurityException.class, () -> {
            runnerExecutor.execute(
                    "exec-symlink", "owner-1", "ws-1",
                    RunnerTemplateType.MAVEN_TEST,
                    AgentToolRiskLevel.LOW, null,
                    linkDir.toString(),
                    List.of("echo", "symlink"),
                    60
            );
        });
    }

    @Test
    void shouldFailClosedInsteadOfExecutingCommandsOnTheHost() throws Exception {
        Path workDir = tempDir.resolve("exec-workspace");
        Files.createDirectories(workDir);
        Path marker = workDir.resolve("host-execution-must-not-happen");

        assertThrows(IllegalStateException.class, () -> runnerExecutor.execute(
                    "exec-safe-1", "owner-1", "ws-1",
                    RunnerTemplateType.MAVEN_TEST,
                    AgentToolRiskLevel.LOW, null,
                    workDir.toRealPath().toString(),
                    List.of("touch", marker.toString()),
                    10
            ));
        assertFalse(Files.exists(marker), "Runner 不可用时绝不能退回宿主机执行命令");
    }

    @Test
    void shouldOnlyDispatchSignedEnvelopeThroughRunnerTransport() throws Exception {
        Path workDir = tempDir.resolve("socket-workspace");
        Files.createDirectories(workDir);
        AtomicReference<RunnerSignedEnvelope> received = new AtomicReference<>();
        RunnerTransport transport = (envelope, workspaceId, templateType) -> {
            received.set(envelope);
            return new RunnerExecutionResult(
                    envelope.executionId(), workspaceId, templateType, "SUCCEEDED", 0,
                    envelope.commandTokens(), "ok", "", true, 12L, Instant.now());
        };
        IsolatedRunnerExecutor executor = new IsolatedRunnerExecutor(securityService, transport);

        RunnerExecutionResult result = executor.execute(
                "exec-socket", "owner-socket", "ws-socket",
                RunnerTemplateType.MAVEN_TEST, AgentToolRiskLevel.LOW, null,
                workDir.toRealPath().toString(), List.of("mvn", "test"), 30);

        assertTrue(result.success());
        assertNotNull(received.get());
        assertEquals(RunnerIsolationMode.LOCAL_RUNNER, received.get().isolationMode());
        assertEquals("owner-socket", received.get().ownerId());
        assertEquals("ws-socket", received.get().workspaceId());
        assertEquals(RunnerTemplateType.MAVEN_TEST, received.get().templateType());
        assertEquals(AgentToolRiskLevel.LOW, received.get().riskLevel());
        assertEquals(List.of("mvn", "test"), received.get().commandTokens());
    }

    @Test
    void shouldVerifyEnvelopeSignedByPythonGoldenProtocol() {
        RunnerSecurityService verifier = new RunnerSecurityService(
                "runner-test-secret-that-is-at-least-32-bytes");
        RunnerSignedEnvelope pythonEnvelope = new RunnerSignedEnvelope(
                "v1", "golden-execution", "golden-owner", "golden-workspace",
                RunnerTemplateType.MAVEN_TEST, AgentToolRiskLevel.LOW,
                "/workspace/golden", List.of("mvn", "test", "-Dtest=GoldenTest"),
                RunnerIsolationMode.LOCAL_RUNNER, true, "512m", "1.0", 60,
                Instant.parse("2026-09-06T04:00:00Z"), null,
                Instant.parse("2026-09-06T04:10:00Z"), "golden-nonce",
                "157afb2c890adf4a129954bfb9fd7bb4548dc357cc03577ffc63f72541392d15"
        );

        assertTrue(verifier.verifyEnvelope(
                pythonEnvelope, Instant.parse("2026-09-06T04:01:00Z")));
    }
}
