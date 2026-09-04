package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.application.AgentGatewayService;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * 统一 Agent 的浏览器安全门面。
 *
 * <p>ownerId 永远来自 Bearer 会话；内部令牌只存在于 Java 到 Python 的请求中。</p>
 */
@RestController
@RequestMapping("/api/assistant/conversations")
public class UnifiedAssistantFacadeController {

    private final AgentGatewayService gateway;
    private final ObjectMapper objectMapper;

    public UnifiedAssistantFacadeController(
            AgentGatewayService gateway,
            ObjectMapper objectMapper
    ) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<JsonNode> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody(required = false) JsonNode body
    ) {
        return json(gateway.post("/internal/assistant/conversations", body, user.id()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JsonNode> get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id
    ) {
        return json(gateway.get("/internal/assistant/conversations/" + id, user.id()));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<JsonNode> message(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestBody JsonNode body
    ) {
        return json(gateway.post(
                "/internal/assistant/conversations/" + id + "/messages",
                body,
                user.id()
        ));
    }

    @PostMapping("/{id}/actions/{actionId}/confirm")
    public ResponseEntity<JsonNode> confirm(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @PathVariable String actionId
    ) {
        return json(gateway.post(
                "/internal/assistant/conversations/" + id
                        + "/actions/" + safeActionId(actionId) + "/confirm",
                null,
                user.id()
        ));
    }

    @PostMapping("/{id}/actions/{actionId}/reject")
    public ResponseEntity<JsonNode> reject(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @PathVariable String actionId
    ) {
        return json(gateway.post(
                "/internal/assistant/conversations/" + id
                        + "/actions/" + safeActionId(actionId) + "/reject",
                null,
                user.id()
        ));
    }

    @PostMapping("/{id}/turns/{turnId}/cancel")
    public ResponseEntity<JsonNode> cancel(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @PathVariable String turnId
    ) {
        return json(gateway.post(
                "/internal/assistant/conversations/" + id
                        + "/turns/" + safeActionId(turnId) + "/cancel",
                null,
                user.id()
        ));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> events(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
    ) {
        long afterSequence = parseSequence(lastEventId);
        AgentGatewayService.GatewayResponse response = gateway.get(
                "/internal/assistant/conversations/" + id
                        + "/events?afterSequence=" + afterSequence,
                user.id()
        );
        StringBuilder stream = new StringBuilder(": heartbeat\n\n");
        if (response.body().isArray()) {
            for (JsonNode event : response.body()) {
                long sequence = event.path("sequence").asLong(-1);
                String type = event.path("type").asText("TURN_FAILED");
                if (sequence <= afterSequence || !type.matches("[A-Z_]{1,40}")) {
                    continue;
                }
                stream.append("id: ").append(sequence).append('\n')
                        .append("event: ").append(type).append('\n')
                        .append("data: ").append(objectMapper.writeValueAsString(event))
                        .append("\n\n");
            }
        }
        return ResponseEntity.status(response.status())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream.toString());
    }

    private ResponseEntity<JsonNode> json(AgentGatewayService.GatewayResponse response) {
        return ResponseEntity.status(response.status()).body(response.body());
    }

    private static long parseSequence(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String safeActionId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("操作标识不合法");
        }
        return value;
    }
}
