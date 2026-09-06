package com.moxiao.studypilot.agent.runner;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnixSocketRunnerClientTest {

    @Test
    void exchangesOneBoundedJsonMessageWithLocalRunner() throws Exception {
        Path socket = Path.of("/tmp", "studypilot-test-" + UUID.randomUUID() + ".sock");
        ObjectMapper mapper = new ObjectMapper();
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            var executor = Executors.newSingleThreadExecutor();
            var requestFuture = executor.submit(() -> serveOnce(server, mapper));
            RunnerSignedEnvelope envelope = new RunnerSecurityService(
                    "test-secret-32-bytes-long-123456").createEnvelope(
                    "execution-1", "owner-1", "workspace-1", RunnerTemplateType.MAVEN_TEST,
                    com.moxiao.studypilot.agent.tool.AgentToolRiskLevel.LOW,
                    "/workspace", List.of("mvn", "test"), RunnerIsolationMode.LOCAL_RUNNER,
                    true, "512m", "1.0", 60, null);

            RunnerExecutionResult result = new UnixSocketRunnerClient(
                    socket.toString(), mapper, 65_536).execute(
                    envelope, "workspace-1", RunnerTemplateType.MAVEN_TEST);

            JsonNode request = requestFuture.get(5, TimeUnit.SECONDS);
            assertEquals("execution-1", request.path("envelope").path("executionId").asText());
            assertEquals("workspace-1", request.path("workspaceId").asText());
            assertEquals("MAVEN_TEST", request.path("templateType").asText());
            assertTrue(result.success());
            assertEquals("runner-ok", result.stdoutSummary());
            executor.shutdownNow();
        } finally {
            Files.deleteIfExists(socket);
        }
    }

    @Test
    void refusesSocketAccessibleByOtherOperatingSystemUsers() throws Exception {
        Path socket = Path.of("/tmp", "studypilot-permissive-" + UUID.randomUUID() + ".sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
        }
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                    new UnixSocketRunnerClient(socket.toString(), new ObjectMapper(), 65_536)
                            .execute(null, "workspace-1", RunnerTemplateType.MAVEN_TEST));
            assertTrue(error.getMessage().contains("权限"));
        } finally {
            Files.deleteIfExists(socket);
        }
    }

    private JsonNode serveOnce(ServerSocketChannel server, ObjectMapper mapper) throws IOException {
        try (SocketChannel channel = server.accept();
             BufferedReader reader = new BufferedReader(Channels.newReader(
                     channel, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(Channels.newWriter(
                     channel, StandardCharsets.UTF_8))) {
            JsonNode request = mapper.readTree(reader.readLine());
            writer.write(mapper.writeValueAsString(new RunnerExecutionResult(
                    "execution-1", "workspace-1", RunnerTemplateType.MAVEN_TEST,
                    "SUCCEEDED", 0, List.of("mvn", "test"), "runner-ok", "",
                    true, 5L, Instant.now())));
            writer.newLine();
            writer.flush();
            return request;
        }
    }
}
