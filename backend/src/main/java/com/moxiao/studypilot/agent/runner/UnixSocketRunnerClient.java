package com.moxiao.studypilot.agent.runner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** 使用一问一答的有界 JSON 行协议调用独立 Local Runner。 */
@Component
public class UnixSocketRunnerClient implements RunnerTransport {

    private final Path socketPath;
    private final ObjectMapper objectMapper;
    private final int maxResponseCharacters;

    public UnixSocketRunnerClient(
            @Value("${studypilot.runner.socket-path:/tmp/studypilot-runner/runner.sock}")
            String socketPath,
            ObjectMapper objectMapper,
            @Value("${studypilot.runner.max-response-characters:65536}")
            int maxResponseCharacters
    ) {
        this.socketPath = Path.of(socketPath).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.maxResponseCharacters = maxResponseCharacters;
    }

    @Override
    public RunnerExecutionResult execute(
            RunnerSignedEnvelope envelope,
            String workspaceId,
            RunnerTemplateType templateType
    ) {
        validateSocketPath();
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            try (BufferedWriter writer = new BufferedWriter(Channels.newWriter(
                    channel, StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(Channels.newReader(
                         channel, StandardCharsets.UTF_8))) {
                writer.write(objectMapper.writeValueAsString(
                        new RunnerSocketRequest(envelope, workspaceId, templateType)));
                writer.newLine();
                writer.flush();
                String response = readBoundedLine(reader);
                if (response == null || response.isBlank()) {
                    throw new IllegalStateException("Local Runner 返回了空响应");
                }
                return objectMapper.readValue(response, RunnerExecutionResult.class);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Local Runner Unix Socket 不可用: " + socketPath, exception);
        }
    }

    private void validateSocketPath() {
        if (!Files.exists(socketPath) || Files.isSymbolicLink(socketPath)) {
            throw new IllegalStateException("Local Runner Unix Socket 不可用: " + socketPath);
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(socketPath);
            boolean exposed = permissions.stream().anyMatch(permission -> switch (permission) {
                case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
                     OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
                default -> false;
            });
            if (exposed) {
                throw new IllegalStateException("Local Runner Unix Socket 权限必须限制为所有者访问");
            }
        } catch (UnsupportedOperationException | IOException exception) {
            throw new IllegalStateException("无法验证 Local Runner Unix Socket 权限", exception);
        }
        if (maxResponseCharacters < 1024 || maxResponseCharacters > 1_000_000) {
            throw new IllegalStateException("Runner 响应上限配置不安全");
        }
    }

    private String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder value = new StringBuilder(Math.min(maxResponseCharacters, 8192));
        int current;
        while ((current = reader.read()) != -1 && current != '\n') {
            if (value.length() >= maxResponseCharacters) {
                throw new IllegalStateException("Local Runner 响应超过安全上限");
            }
            value.append((char) current);
        }
        return current == -1 && value.isEmpty() ? null : value.toString();
    }

    private record RunnerSocketRequest(
            RunnerSignedEnvelope envelope,
            String workspaceId,
            RunnerTemplateType templateType
    ) { }
}
