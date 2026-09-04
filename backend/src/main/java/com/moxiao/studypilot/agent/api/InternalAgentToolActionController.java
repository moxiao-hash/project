package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.tool.AgentToolActionResponse;
import com.moxiao.studypilot.agent.tool.AgentToolActionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/agent-tool-actions")
public class InternalAgentToolActionController {
    private final AgentToolActionService service;

    public InternalAgentToolActionController(AgentToolActionService service) {
        this.service = service;
    }

    @PostMapping("/{actionId}/confirm")
    public AgentToolActionResponse confirm(
            @PathVariable String actionId,
            @Valid @RequestBody ConfirmAgentExecutionRequest request
    ) {
        return service.confirm(request.ownerId(), actionId);
    }

    @PostMapping("/{actionId}/reject")
    public AgentToolActionResponse reject(
            @PathVariable String actionId,
            @Valid @RequestBody ConfirmAgentExecutionRequest request
    ) {
        return service.reject(request.ownerId(), actionId);
    }
}
