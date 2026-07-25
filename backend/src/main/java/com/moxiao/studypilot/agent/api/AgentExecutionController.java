package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent-executions")
public class AgentExecutionController {

    private final AgentGovernanceService service;

    public AgentExecutionController(AgentGovernanceService service) {
        this.service = service;
    }

    @GetMapping
    public List<AgentExecutionResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return service.listExecutions(user.id()).stream()
                .map(AgentExecutionResponse::from)
                .toList();
    }

    @PostMapping("/{executionId}/confirm")
    public AgentExecutionResponse confirm(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String executionId
    ) {
        return AgentExecutionResponse.from(service.confirm(user.id(), executionId));
    }
}
