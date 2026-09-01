package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.application.RoadmapGraduationJobService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/roadmap-graduation-jobs")
public class InternalRoadmapGraduationJobController {
    private final RoadmapGraduationJobService service;

    public InternalRoadmapGraduationJobController(RoadmapGraduationJobService service) {
        this.service = service;
    }

    @PostMapping("/claim")
    public RoadmapGraduationJobResponse claim(
            @Valid @RequestBody ClaimRoadmapQuizJobRequest request
    ) {
        return service.claim(request.workerId(), request.leaseSeconds());
    }

    @PostMapping("/{id}/complete")
    public RoadmapGraduationJobResponse complete(
            @PathVariable String id,
            @Valid @RequestBody CompleteRoadmapQuizJobRequest request
    ) {
        return service.complete(id, request);
    }

    @PostMapping("/{id}/fail")
    public RoadmapGraduationJobResponse fail(
            @PathVariable String id,
            @Valid @RequestBody FailRoadmapQuizJobRequest request
    ) {
        return service.fail(id, request.workerId(), request.leaseToken(), request.error());
    }
}
