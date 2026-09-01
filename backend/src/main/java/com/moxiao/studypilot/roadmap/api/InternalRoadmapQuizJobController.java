package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.application.RoadmapLearningLoopService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/roadmap-quiz-generation-jobs")
public class InternalRoadmapQuizJobController {
    private final RoadmapLearningLoopService service;

    public InternalRoadmapQuizJobController(RoadmapLearningLoopService service) {
        this.service = service;
    }

    @PostMapping("/claim")
    public RoadmapQuizJobPayload claim(@Valid @RequestBody ClaimRoadmapQuizJobRequest request) {
        return service.claimQuizJob(request.workerId(), request.leaseSeconds());
    }

    @GetMapping("/{jobId}/context")
    public RoadmapQuizContextResponse context(
            @PathVariable String jobId,
            @RequestParam String workerId,
            @RequestParam String leaseToken
    ) {
        return service.quizJobContext(jobId, workerId, leaseToken);
    }

    @PostMapping("/{jobId}/heartbeat")
    public RoadmapQuizJobPayload heartbeat(
            @PathVariable String jobId,
            @Valid @RequestBody RenewRoadmapQuizJobLeaseRequest request
    ) {
        return service.heartbeatQuizJob(
                jobId, request.workerId(), request.leaseToken(), request.leaseSeconds());
    }

    @PostMapping("/{jobId}/fail")
    public RoadmapQuizJobPayload fail(
            @PathVariable String jobId,
            @Valid @RequestBody FailRoadmapQuizJobRequest request
    ) {
        return service.failQuizJob(jobId, request.workerId(), request.leaseToken(), request.error());
    }

    @PostMapping("/{jobId}/complete")
    public RoadmapQuizJobPayload complete(
            @PathVariable String jobId,
            @Valid @RequestBody CompleteRoadmapQuizJobRequest request
    ) {
        if (request.quiz() != null) {
            return service.createAndCompleteQuizJob(
                    jobId, request.workerId(), request.leaseToken(), request.quiz());
        }
        if (request.quizId() == null || request.quizId().isBlank()) {
            throw new IllegalArgumentException("quiz 与 quizId 必须提供一个");
        }
        return service.completeQuizJob(
                jobId, request.workerId(), request.leaseToken(), request.quizId());
    }
}
