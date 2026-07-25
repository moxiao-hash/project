package com.moxiao.studypilot.dashboard.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.dashboard.application.DashboardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public DashboardResponse get(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.get(user.id());
    }
}
