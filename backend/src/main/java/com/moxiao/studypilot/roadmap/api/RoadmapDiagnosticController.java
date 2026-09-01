package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.roadmap.application.RoadmapDiagnosticService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/roadmaps/current/diagnostic")
public class RoadmapDiagnosticController {
    private final RoadmapDiagnosticService service;

    public RoadmapDiagnosticController(RoadmapDiagnosticService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoadmapDiagnosticResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateRoadmapDiagnosticRequest request
    ) {
        return service.create(user.id(), request);
    }

    @GetMapping
    public RoadmapDiagnosticResponse current(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return service.currentDiagnostic(user.id());
    }
}
