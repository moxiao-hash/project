package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.tool.AgentToolDescriptor;
import com.moxiao.studypilot.agent.tool.AgentToolInvocationRequest;
import com.moxiao.studypilot.agent.tool.AgentToolInvocationResponse;
import com.moxiao.studypilot.agent.tool.AgentToolRegistry;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/agent-tools")
public class InternalAgentToolController {
    private final AgentToolRegistry registry;

    public InternalAgentToolController(AgentToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/catalog")
    public List<AgentToolDescriptor> catalog() {
        return registry.catalog();
    }

    @PostMapping("/{toolName}/invoke")
    public AgentToolInvocationResponse invoke(
            @PathVariable String toolName,
            @Valid @RequestBody AgentToolInvocationRequest request
    ) {
        return registry.invoke(toolName, request);
    }
}
