package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.roadmap.application.RoadmapLearningLoopService;
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

import java.util.List;

@RestController
@RequestMapping("/api/roadmap-nodes/{nodeId}")
public class RoadmapLearningLoopController {
    private final RoadmapLearningLoopService service;

    public RoadmapLearningLoopController(RoadmapLearningLoopService service) {
        this.service = service;
    }

    @PostMapping("/check-ins")
    @ResponseStatus(HttpStatus.CREATED)
    public RoadmapNodeCheckInResponse checkIn(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String nodeId,
            @Valid @RequestBody CreateRoadmapNodeCheckInRequest request
    ) {
        return service.checkIn(user.id(), nodeId, request);
    }

    @GetMapping("/check-ins")
    public List<RoadmapNodeCheckInResponse> checkIns(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String nodeId
    ) {
        return service.checkIns(user.id(), nodeId);
    }

    @GetMapping("/quiz")
    public RoadmapNodeQuizResponse quiz(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String nodeId
    ) {
        return service.quiz(user.id(), nodeId);
    }

    @PostMapping("/quiz-retries")
    @ResponseStatus(HttpStatus.CREATED)
    public RoadmapQuizGenerationResponse retryQuiz(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String nodeId,
            @Valid @RequestBody RetryRoadmapQuizRequest request
    ) {
        return service.retryQuiz(user.id(), nodeId, request);
    }

    @PostMapping("/quick-verification")
    @ResponseStatus(HttpStatus.CREATED)
    public RoadmapQuizGenerationResponse quickVerification(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String nodeId,
            @Valid @RequestBody RetryRoadmapQuizRequest request
    ) {
        return service.quickVerification(user.id(), nodeId, request);
    }
}
