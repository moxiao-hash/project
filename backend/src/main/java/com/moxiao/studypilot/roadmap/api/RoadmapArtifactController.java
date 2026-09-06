package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.roadmap.application.ArtifactReviewService;
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
    private final ArtifactReviewService reviewService;

    public RoadmapArtifactController(
            RoadmapArtifactService service,
            ArtifactReviewService reviewService
    ) {
        this.service = service;
        this.reviewService = reviewService;
    }

    @PostMapping("/{artifactId}/review-previews")
    @ResponseStatus(HttpStatus.CREATED)
    public ArtifactReviewPreviewResponse createReviewPreview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String artifactId,
            @Valid @RequestBody CreateArtifactReviewPreviewRequest request
    ) {
        return reviewService.createPreview(user.id(), artifactId, request);
    }

    @PostMapping("/{artifactId}/review-previews/{previewId}/confirm")
    public RoadmapArtifactResponse confirmReviewPreview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String artifactId,
            @PathVariable String previewId
    ) {
        return reviewService.confirm(user.id(), artifactId, previewId);
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

    @PostMapping("/{artifactId}/evaluate")
    public RoadmapArtifactResponse evaluate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String artifactId
    ) {
        return service.evaluate(user.id(), artifactId);
    }

    @PostMapping("/{artifactId}/accept")
    public RoadmapArtifactResponse accept(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String artifactId
    ) {
        return service.accept(user.id(), artifactId);
    }

    @PostMapping("/{artifactId}/reject")
    public RoadmapArtifactResponse reject(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String artifactId,
            @Valid @RequestBody(required = false) RejectRoadmapArtifactRequest request
    ) {
        return service.reject(user.id(), artifactId, request != null ? request.reason() : null);
    }

}
