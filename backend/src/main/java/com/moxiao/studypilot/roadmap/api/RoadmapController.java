package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.roadmap.application.RoadmapEnrollmentService;
import com.moxiao.studypilot.roadmap.application.RoadmapQueryService;
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
@RequestMapping("/api")
public class RoadmapController {

    private final RoadmapEnrollmentService enrollmentService;
    private final RoadmapQueryService queryService;

    public RoadmapController(
            RoadmapEnrollmentService enrollmentService,
            RoadmapQueryService queryService
    ) {
        this.enrollmentService = enrollmentService;
        this.queryService = queryService;
    }

    @PostMapping("/roadmap-enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public RoadmapEnrollmentResponse enroll(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateRoadmapEnrollmentRequest request
    ) {
        return enrollmentService.enroll(user.id(), request.roadmapCode(), request.templateVersion());
    }

    @GetMapping("/roadmaps/current")
    public RoadmapEnrollmentResponse current(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return queryService.current(user.id());
    }

    @GetMapping("/roadmaps/current/map")
    public RoadmapMapResponse currentMap(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return queryService.currentMap(user.id());
    }

    @GetMapping("/roadmaps/current/stages/{stageId}")
    public RoadmapStageResponse stage(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String stageId
    ) {
        return queryService.currentStage(user.id(), stageId);
    }

    @GetMapping("/roadmaps/current/modules/{moduleId}")
    public RoadmapModuleResponse module(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String moduleId
    ) {
        return queryService.currentModule(user.id(), moduleId);
    }

    @GetMapping("/roadmaps/current/nodes/{nodeId}")
    public RoadmapNodeResponse node(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String nodeId
    ) {
        return queryService.currentNode(user.id(), nodeId);
    }
}
