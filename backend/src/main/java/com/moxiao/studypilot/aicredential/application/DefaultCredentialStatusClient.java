package com.moxiao.studypilot.aicredential.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 查询 Python 进程实际加载的开发环境默认配置，只接收配置状态和脱敏尾号。
 */
@Component
public class DefaultCredentialStatusClient {

    private final URI endpoint;
    private final String internalToken;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DefaultCredentialStatusClient(
            @Value("${studypilot.ai-service-base-url:http://localhost:8000}") String baseUrl,
            @Value("${studypilot.internal-service-token}") String internalToken,
            ObjectMapper objectMapper
    ) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "")
                + "/internal/model/default-credentials");
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public DefaultStatuses fetch() {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(3))
                .header("Accept", "application/json")
                .header("X-Internal-Service-Token", internalToken)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                return DefaultStatuses.unavailable();
            }
            JsonNode root = objectMapper.readTree(response.body());
            return new DefaultStatuses(
                    safe(root.path("deepseek")),
                    safe(root.path("tavily")),
                    true
            );
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return DefaultStatuses.unavailable();
        }
    }

    private SafeStatus safe(JsonNode node) {
        return new SafeStatus(
                node.path("configured").asBoolean(false),
                node.path("maskedSuffix").isTextual()
                        ? node.path("maskedSuffix").asText() : null
        );
    }

    public record SafeStatus(boolean configured, String maskedSuffix) {}

    public record DefaultStatuses(
            SafeStatus deepseek,
            SafeStatus tavily,
            boolean available
    ) {
        static DefaultStatuses unavailable() {
            SafeStatus empty = new SafeStatus(false, null);
            return new DefaultStatuses(empty, empty, false);
        }
    }
}
