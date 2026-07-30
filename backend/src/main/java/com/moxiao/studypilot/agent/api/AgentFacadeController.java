package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.application.AgentGatewayService;
import com.moxiao.studypilot.agent.application.AgentGatewayService.GatewayResponse;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 浏览器可调用的 Agent API。
 *
 * <p>该控制器只负责认证边界和契约转发。核心学习数据仍由 Java 维护，
 * Python 只通过现有 /internal 工具接口读取或提交受治理的操作。</p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentFacadeController {

    private final AgentGatewayService gateway;

    public AgentFacadeController(AgentGatewayService gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/plan-conversations")
    public ResponseEntity<JsonNode> createPlanConversation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody JsonNode body
    ) {
        return response(gateway.post("/internal/agent/conversations", body, user.id()));
    }

    @PostMapping("/plan-conversations/{id}/messages")
    public ResponseEntity<JsonNode> sendPlanMessage(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestBody JsonNode body
    ) {
        return response(gateway.post(
                "/internal/agent/conversations/" + id + "/messages",
                body,
                user.id()
        ));
    }

    @GetMapping("/plan-conversations/{id}")
    public ResponseEntity<JsonNode> getPlanConversation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id
    ) {
        return response(gateway.get("/internal/agent/conversations/" + id, user.id()));
    }

    @PostMapping("/plan-conversations/{id}/confirm")
    public ResponseEntity<JsonNode> confirmPlanConversation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id
    ) {
        return response(gateway.post(
                "/internal/agent/conversations/" + id + "/confirm",
                null,
                user.id()
        ));
    }

    @PostMapping("/task-conversations")
    public ResponseEntity<JsonNode> createTaskConversation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody JsonNode body
    ) {
        return response(gateway.post(
                "/internal/agent/task-conversations",
                body,
                user.id()
        ));
    }

    @PostMapping("/task-conversations/{id}/messages")
    public ResponseEntity<JsonNode> sendTaskMessage(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestBody JsonNode body
    ) {
        return response(gateway.post(
                "/internal/agent/task-conversations/" + id + "/messages",
                body,
                user.id()
        ));
    }

    @GetMapping("/task-conversations/{id}")
    public ResponseEntity<JsonNode> getTaskConversation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id
    ) {
        return response(gateway.get(
                "/internal/agent/task-conversations/" + id,
                user.id()
        ));
    }

    @PostMapping("/task-conversations/{id}/confirm")
    public ResponseEntity<JsonNode> confirmTaskConversation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id
    ) {
        return response(gateway.post(
                "/internal/agent/task-conversations/" + id + "/confirm",
                null,
                user.id()
        ));
    }

    @PostMapping("/knowledge-conversations")
    public ResponseEntity<JsonNode> createKnowledgeConversation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody JsonNode body
    ) {
        return response(gateway.post(
                "/internal/knowledge/conversations",
                body,
                user.id()
        ));
    }

    @PostMapping("/knowledge-conversations/{id}/messages")
    public ResponseEntity<JsonNode> sendKnowledgeMessage(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestBody JsonNode body
    ) {
        return response(gateway.post(
                "/internal/knowledge/conversations/" + id + "/messages",
                body,
                user.id()
        ));
    }

    @GetMapping("/knowledge-conversations/{id}")
    public ResponseEntity<JsonNode> getKnowledgeConversation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id
    ) {
        return response(gateway.get(
                "/internal/knowledge/conversations/" + id,
                user.id()
        ));
    }

    @PostMapping("/teaching-conversations")
    public ResponseEntity<JsonNode> createTeachingConversation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody JsonNode body
    ) {
        return response(gateway.post(
                "/internal/teaching/conversations",
                body,
                user.id()
        ));
    }

    @PostMapping("/teaching-conversations/{id}/messages")
    public ResponseEntity<JsonNode> sendTeachingMessage(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestBody JsonNode body
    ) {
        return response(gateway.post(
                "/internal/teaching/conversations/" + id + "/messages",
                body,
                user.id()
        ));
    }

    @GetMapping("/teaching-conversations/{id}")
    public ResponseEntity<JsonNode> getTeachingConversation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id
    ) {
        return response(gateway.get(
                "/internal/teaching/conversations/" + id,
                user.id()
        ));
    }

    @PostMapping("/plan-adjustments/analyze")
    public ResponseEntity<JsonNode> analyzePlanAdjustment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody JsonNode body
    ) {
        return response(gateway.post(
                "/internal/agent/plan-adjustments/analyze",
                body,
                user.id()
        ));
    }

    @GetMapping("/plan-adjustments/{id}")
    public ResponseEntity<JsonNode> getPlanAdjustment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id
    ) {
        return response(gateway.get(
                "/internal/agent/plan-adjustments/" + id,
                user.id()
        ));
    }

    @PostMapping("/plan-adjustments/{id}/confirm")
    public ResponseEntity<JsonNode> confirmPlanAdjustment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id
    ) {
        return response(gateway.post(
                "/internal/agent/plan-adjustments/" + id + "/confirm",
                null,
                user.id()
        ));
    }

    @PostMapping("/quizzes/generate")
    public ResponseEntity<JsonNode> generateQuiz(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody JsonNode body
    ) {
        return response(gateway.generateQuiz(body, user.id()));
    }

    private ResponseEntity<JsonNode> response(GatewayResponse result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
