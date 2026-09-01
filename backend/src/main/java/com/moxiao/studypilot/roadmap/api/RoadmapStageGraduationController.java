package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.roadmap.application.RoadmapStageGraduationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/roadmaps/current/stages/{stageId}/graduation")
public class RoadmapStageGraduationController {
    private final RoadmapStageGraduationService service;

    public RoadmapStageGraduationController(RoadmapStageGraduationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoadmapStageGraduationResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String stageId,
            @Valid @RequestBody CreateStageGraduationRequest request
    ) {
        return service.create(user.id(), stageId, request);
    }

    @GetMapping
    public RoadmapStageGraduationResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String stageId
    ) {
        return service.get(user.id(), stageId);
    }
}
