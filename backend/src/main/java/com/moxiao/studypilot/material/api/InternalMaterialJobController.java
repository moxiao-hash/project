package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.material.application.MaterialJobService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/material-processing-jobs")
public class InternalMaterialJobController {

    private final MaterialJobService service;

    public InternalMaterialJobController(MaterialJobService service) {
        this.service = service;
    }

    @PostMapping("/claim")
    public MaterialJobResponse claim(@Valid @RequestBody ClaimMaterialJobRequest request) {
        return service.claim(request.workerId(), request.leaseSeconds());
    }

    @PostMapping("/{jobId}/heartbeat")
    public MaterialJobResponse heartbeat(
            @PathVariable String jobId,
            @Valid @RequestBody ClaimMaterialJobRequest request
    ) {
        return service.heartbeat(jobId, request.workerId(), request.leaseSeconds());
    }

    @PostMapping("/{jobId}/fail")
    public MaterialJobResponse fail(
            @PathVariable String jobId,
            @Valid @RequestBody FailMaterialJobRequest request
    ) {
        return service.fail(jobId, request.workerId(), request.error());
    }
}
