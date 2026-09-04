package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.roadmap.application.RoadmapArtifactService;
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
@RequestMapping("/api/roadmap-artifacts")
public class RoadmapArtifactController {
    private final RoadmapArtifactService service;

    public RoadmapArtifactController(RoadmapArtifactService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoadmapArtifactResponse submit(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateRoadmapArtifactRequest request
    ) {
        return service.submit(user.id(), request);
    }

    @GetMapping("/{artifactId}")
    public RoadmapArtifactResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String artifactId
    ) {
        return service.artifact(user.id(), artifactId);
    }
}
