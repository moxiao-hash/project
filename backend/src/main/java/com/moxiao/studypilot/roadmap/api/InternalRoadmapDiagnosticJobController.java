package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.application.RoadmapDiagnosticJobService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/roadmap-diagnostic-jobs")
public class InternalRoadmapDiagnosticJobController {
    private final RoadmapDiagnosticJobService service;

    public InternalRoadmapDiagnosticJobController(RoadmapDiagnosticJobService service) {
        this.service = service;
    }

    @PostMapping("/claim")
    public RoadmapDiagnosticJobResponse claim(
            @Valid @RequestBody ClaimRoadmapQuizJobRequest request
    ) {
        return service.claim(request.workerId(), request.leaseSeconds());
    }

    @PostMapping("/{id}/heartbeat")
    public RoadmapDiagnosticJobResponse heartbeat(
            @PathVariable String id,
            @Valid @RequestBody RenewRoadmapQuizJobLeaseRequest request
    ) {
        return service.heartbeat(id, request.workerId(), request.leaseToken(), request.leaseSeconds());
    }

    @PostMapping("/{id}/complete")
    public RoadmapDiagnosticJobResponse complete(
            @PathVariable String id,
            @Valid @RequestBody CompleteRoadmapQuizJobRequest request
    ) {
        return service.complete(id, request);
    }

    @PostMapping("/{id}/fail")
    public RoadmapDiagnosticJobResponse fail(
            @PathVariable String id,
            @Valid @RequestBody FailRoadmapQuizJobRequest request
    ) {
        return service.fail(id, request.workerId(), request.leaseToken(), request.error());
    }
}
