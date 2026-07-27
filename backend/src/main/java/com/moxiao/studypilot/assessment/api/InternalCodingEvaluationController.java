package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.application.QuizService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/coding-evaluation-jobs")
public class InternalCodingEvaluationController {
    private final QuizService service;

    public InternalCodingEvaluationController(QuizService service) {
        this.service = service;
    }

    @PostMapping("/claim")
    public QuizService.CodingJobPayload claim(@Valid @RequestBody ClaimRequest request) {
        return service.claimCodingJob(request.workerId(), request.leaseSeconds());
    }

    @PostMapping("/{jobId}/heartbeat")
    public void heartbeat(
            @PathVariable String jobId,
            @Valid @RequestBody ClaimRequest request
    ) {
        service.heartbeatCodingJob(jobId, request.workerId(), request.leaseSeconds());
    }

    @PostMapping("/{jobId}/complete")
    public QuizAttemptResponse complete(
            @PathVariable String jobId,
            @Valid @RequestBody CompleteRequest request
    ) {
        return service.completeCodingJob(jobId, request.workerId(), request.evaluations());
    }

    @PostMapping("/{jobId}/fail")
    public void fail(
            @PathVariable String jobId,
            @Valid @RequestBody FailRequest request
    ) {
        service.failCodingJob(jobId, request.workerId(), request.error());
    }

    public record ClaimRequest(
            @NotBlank String workerId,
            @Min(10) @Max(600) int leaseSeconds
    ) {
    }

    public record CompleteRequest(
            @NotBlank String workerId,
            @NotEmpty List<Map<String, Object>> evaluations
    ) {
    }

    public record FailRequest(
            @NotBlank String workerId,
            @NotBlank String error
    ) {
    }
}
