package com.moxiao.studypilot.agent.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * Java 业务后端到 Python Agent 服务的唯一公共门面客户端。
 *
 * <p>浏览器永远看不到内部令牌，也不能决定 ownerId。调用没有自动重试：
 * 生成计划、确认任务等操作可能产生写入，盲目重试会破坏幂等边界。</p>
 */
@Service
public class AgentGatewayService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Set<Integer> FORWARDED_ERROR_STATUSES =
            Set.of(400, 404, 409, 422, 429, 502, 503, 504);

    private final URI baseUri;
    private final String internalToken;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AgentGatewayService(
            @Value("${studypilot.ai-service-base-url:http://localhost:8000}")
            String baseUrl,
            @Value("${studypilot.internal-service-token}")
            String internalToken,
            ObjectMapper objectMapper
    ) {
        this.baseUri = URI.create(stripTrailingSlash(baseUrl));
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public GatewayResponse post(String path, JsonNode body, String ownerId) {
        ObjectNode forwardedBody = ownerScopedBody(body, ownerId);
        HttpRequest request = baseRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(forwardedBody)
                ))
                .build();
        return exchange(request);
    }

    public GatewayResponse get(String path, String ownerId) {
        String separator = path.contains("?") ? "&" : "?";
        String scopedPath = path + separator + "ownerId="
                + URLEncoder.encode(ownerId, StandardCharsets.UTF_8);
        return exchange(baseRequest(scopedPath).GET().build());
    }

    /**
     * 测验生成的内部响应包含答案和参考实现，只允许向浏览器返回新测验 ID。
     */
    public GatewayResponse generateQuiz(JsonNode body, String ownerId) {
        GatewayResponse internal = post(
                "/internal/assessment/quizzes/generate",
                body,
                ownerId
        );
        JsonNode quizId = internal.body().get("id");
        if (quizId == null || !quizId.isTextual() || quizId.asText().isBlank()) {
            throw new AgentGatewayException(
                    HttpStatus.BAD_GATEWAY,
                    "AI 服务返回了无效的测验标识"
            );
        }
        ObjectNode publicBody = objectMapper.createObjectNode();
        publicBody.put("quizId", quizId.asText());
        return new GatewayResponse(internal.status(), publicBody);
    }

    HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("X-Internal-Service-Token", internalToken);
    }

    private GatewayResponse exchange(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            JsonNode body = readBody(response.body());
            if (response.statusCode() >= 400) {
                throw upstreamError(response, body);
            }
            return new GatewayResponse(
                    HttpStatusCode.valueOf(response.statusCode()),
                    publicBody(body)
            );
        } catch (HttpTimeoutException exception) {
            throw new AgentGatewayException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "AI 服务响应超时，请稍后重试"
            );
        } catch (IOException exception) {
            throw new AgentGatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 服务暂时不可用"
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentGatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 服务调用被中断"
            );
        }
    }

    private AgentGatewayException upstreamError(
            HttpResponse<String> response,
            JsonNode body
    ) {
        int upstreamStatus = response.statusCode();
        int publicStatus = FORWARDED_ERROR_STATUSES.contains(upstreamStatus)
                ? upstreamStatus
                : HttpStatus.BAD_GATEWAY.value();
        String detail = body.path("detail").asText("AI 服务请求失败");
        return new AgentGatewayException(
                HttpStatusCode.valueOf(publicStatus),
                detail,
                response.headers().firstValue("Retry-After").orElse(null)
        );
    }

    private JsonNode readBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(rawBody);
        } catch (RuntimeException exception) {
            throw new AgentGatewayException(
                    HttpStatus.BAD_GATEWAY,
                    "AI 服务返回了无法解析的响应"
            );
        }
    }

    private JsonNode publicBody(JsonNode body) {
        JsonNode copy = body.deepCopy();
        if (copy instanceof ObjectNode object) {
            object.remove("ownerId");
        }
        return copy;
    }

    private ObjectNode ownerScopedBody(JsonNode body, String ownerId) {
        ObjectNode result = body != null && body.isObject()
                ? (ObjectNode) body.deepCopy()
                : objectMapper.createObjectNode();
        // 无论浏览器是否伪造 ownerId，都以当前 Bearer 会话为准。
        result.put("ownerId", ownerId);
        return result;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record GatewayResponse(HttpStatusCode status, JsonNode body) {
    }
}
