package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.application.RoadmapLearningLoopService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @PostMapping("/{jobId}/heartbeat")
    public RoadmapQuizJobPayload heartbeat(
            @PathVariable String jobId,
            @Valid @RequestBody ClaimRoadmapQuizJobRequest request
    ) {
        return service.heartbeatQuizJob(jobId, request.workerId(), request.leaseSeconds());
    }

    @PostMapping("/{jobId}/fail")
    public RoadmapQuizJobPayload fail(
            @PathVariable String jobId,
            @Valid @RequestBody FailRoadmapQuizJobRequest request
    ) {
        return service.failQuizJob(jobId, request.workerId(), request.error());
    }

    @PostMapping("/{jobId}/complete")
    public RoadmapQuizJobPayload complete(
            @PathVariable String jobId,
            @Valid @RequestBody CompleteRoadmapQuizJobRequest request
    ) {
        return service.completeQuizJob(jobId, request.workerId(), request.quizId());
    }
}
