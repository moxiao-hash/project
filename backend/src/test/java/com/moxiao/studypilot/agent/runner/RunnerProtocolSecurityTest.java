package com.moxiao.studypilot.agent.runner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RunnerProtocolSecurityTest {

    private RunnerSecurityService securityService;
    private IsolatedRunnerExecutor runnerExecutor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        securityService = new RunnerSecurityService("test-secret-32-bytes-long-123456");
        runnerExecutor = new IsolatedRunnerExecutor(securityService, true);
    }

    @Test
    void shouldSignAndVerifyValidEnvelope() {
        RunnerSignedEnvelope envelope = securityService.createEnvelope(
                "exec-001",
                tempDir.toAbsolutePath().toString(),
                List.of("echo", "hello"),
                RunnerIsolationMode.EMULATED_SOCKET,
                true,
                "512m",
                "1.0",
                60
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
                "v1",
                "exec-exp",
                tempDir.toAbsolutePath().toString(),
                List.of("echo", "test"),
                RunnerIsolationMode.EMULATED_SOCKET,
                true,
                "512m",
                "1.0",
                60,
                Instant.now().minus(1, ChronoUnit.HOURS),
                "test-nonce-1",
                "invalid-sig"
        );

        assertFalse(securityService.verifyEnvelope(envelope, Instant.now()));
    }

    @Test
    void shouldRejectTamperedEnvelope() {
        RunnerSignedEnvelope original = securityService.createEnvelope(
                "exec-002",
                tempDir.toAbsolutePath().toString(),
                List.of("echo", "safe"),
                RunnerIsolationMode.EMULATED_SOCKET,
                true,
                "512m",
                "1.0",
                60
        );

        // Tamper command
        RunnerSignedEnvelope tampered = new RunnerSignedEnvelope(
                original.protocolVersion(),
                original.executionId(),
                original.workspacePath(),
                List.of("rm", "-rf", "/"),
                original.isolationMode(),
                original.networkDisabled(),
                original.memoryLimit(),
                original.cpuLimit(),
                original.timeoutSeconds(),
                original.expiresAt(),
                original.nonce(),
                original.signature()
        );

        assertFalse(securityService.verifyEnvelope(tampered, Instant.now()));
    }

    @Test
    void shouldRejectNonceReplayAttack() {
        RunnerSignedEnvelope envelope = securityService.createEnvelope(
                "exec-003",
                tempDir.toAbsolutePath().toString(),
                List.of("echo", "replay"),
                RunnerIsolationMode.EMULATED_SOCKET,
                true,
                "512m",
                "1.0",
                60
        );

        // First verification should pass and consume nonce
        assertTrue(securityService.verifyEnvelope(envelope, Instant.now()));

        // Replay of identical envelope with same nonce should fail
        assertFalse(securityService.verifyEnvelope(envelope, Instant.now()));
    }

    @Test
    void shouldRejectSymlinkEscape() throws Exception {
        Path realDir = tempDir.resolve("real-workspace");
        Files.createDirectories(realDir);

        Path linkDir = tempDir.resolve("symlink-workspace");
        Files.createSymbolicLink(linkDir, realDir);

        assertThrows(SecurityException.class, () -> {
            runnerExecutor.execute(
                    "exec-symlink",
                    "ws-1",
                    RunnerTemplateType.MAVEN_TEST,
                    linkDir.toString(),
                    List.of("echo", "symlink"),
                    60
            );
        });
    }

    @Test
    void shouldExecuteSafelyInEmulatedMode() throws Exception {
        Path workDir = tempDir.resolve("exec-workspace");
        Files.createDirectories(workDir);

        RunnerExecutionResult result = runnerExecutor.execute(
                "exec-safe-1",
                "ws-1",
                RunnerTemplateType.MAVEN_TEST,
                workDir.toRealPath().toString(),
                List.of("echo", "STUDY_PILOT_SUCCESS"),
                10
        );

        assertNotNull(result);
        assertEquals("SUCCEEDED", result.status());
        assertTrue(result.success());
        assertTrue(result.stdoutSummary().contains("STUDY_PILOT_SUCCESS"));
    }
}
