package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.application.AssistantHealthService;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant/health")
public class AssistantHealthController {

    private final AssistantHealthService service;

    public AssistantHealthController(AssistantHealthService service) {
        this.service = service;
    }

    @GetMapping
    public AssistantHealthResponse get(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.summarize(user.id());
    }
}
