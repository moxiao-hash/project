package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/agent-executions")
public class InternalAgentExecutionController {

    private final AgentGovernanceService service;

    public InternalAgentExecutionController(AgentGovernanceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentExecutionResponse create(
            @Valid @RequestBody CreateAgentExecutionRequest request
    ) {
        return AgentExecutionResponse.from(service.createExecution(request));
    }

    @PatchMapping("/{executionId}")
    public AgentExecutionResponse update(
            @PathVariable String executionId,
            @Valid @RequestBody UpdateAgentExecutionRequest request
    ) {
        return AgentExecutionResponse.from(service.update(executionId, request));
    }
}
