package com.moxiao.studypilot.agent.automation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/assistant-automation-jobs")
public class InternalAssistantAutomationJobController {

    private final AssistantAutomationService service;

    public InternalAssistantAutomationJobController(AssistantAutomationService service) {
        this.service = service;
    }

    @PostMapping("/claim")
    public ResponseEntity<AutomationJobResponse> claim(
            @Valid @RequestBody ClaimAutomationJobRequest request
    ) {
        AssistantAutomationJobEntity job = service.claim(
                request.workerId(), request.leaseSeconds());
        return job == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(AutomationJobResponse.from(job));
    }

    @PostMapping("/{jobId}/heartbeat")
    public AutomationJobResponse heartbeat(
            @PathVariable String jobId,
            @Valid @RequestBody AutomationJobLeaseRequest request
    ) {
        return AutomationJobResponse.from(service.heartbeat(jobId, request));
    }

    @PostMapping("/{jobId}/complete")
    public AutomationJobResponse complete(
            @PathVariable String jobId,
            @Valid @RequestBody CompleteAutomationJobRequest request
    ) {
        return AutomationJobResponse.from(service.complete(jobId, request));
    }

    @PostMapping("/{jobId}/fail")
    public AutomationJobResponse fail(
            @PathVariable String jobId,
            @Valid @RequestBody FailAutomationJobRequest request
    ) {
        return AutomationJobResponse.from(service.fail(jobId, request));
    }
}
