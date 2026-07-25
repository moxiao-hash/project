package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AgentGovernanceService service;

    public AuditLogController(AgentGovernanceService service) {
        this.service = service;
    }

    @GetMapping
    public List<AuditLogResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.listAuditLogs(user.id()).stream().map(AuditLogResponse::from).toList();
    }
}
