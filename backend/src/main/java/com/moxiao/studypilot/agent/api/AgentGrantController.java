package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent-grants")
public class AgentGrantController {

    private final AgentGovernanceService service;

    public AgentGrantController(AgentGovernanceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentGrantResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateAgentGrantRequest request
    ) {
        return AgentGrantResponse.from(service.createGrant(user.id(), request));
    }

    @GetMapping
    public List<AgentGrantResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.listGrants(user.id()).stream().map(AgentGrantResponse::from).toList();
    }
}
