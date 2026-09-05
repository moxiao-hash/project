package com.moxiao.studypilot.agent.runner;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runner")
public class RunnerController {

    private final RunnerGovernanceService runnerService;

    public RunnerController(RunnerGovernanceService runnerService) {
        this.runnerService = runnerService;
    }

    @PostMapping("/preview")
    public ResponseEntity<RunnerExecutionPreview> preview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RunnerExecutionRequest request
    ) {
        return ResponseEntity.ok(runnerService.preview(user.id(), request));
    }

    @PostMapping("/executions")
    public ResponseEntity<RunnerExecutionResult> execute(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RunnerExecutionRequest request
    ) {
        return ResponseEntity.ok(runnerService.submitExecution(user.id(), request));
    }

    @GetMapping("/executions/{runnerExecutionId}")
    public ResponseEntity<RunnerExecutionResult> get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String runnerExecutionId
    ) {
        return ResponseEntity.ok(runnerService.get(user.id(), runnerExecutionId));
    }

    @PostMapping("/executions/{runnerExecutionId}/confirm")
    public ResponseEntity<RunnerExecutionResult> confirm(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String runnerExecutionId
    ) {
        return ResponseEntity.ok(runnerService.confirmExecution(user.id(), runnerExecutionId));
    }

    @PostMapping("/executions/{runnerExecutionId}/reject")
    public ResponseEntity<RunnerExecutionResult> reject(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String runnerExecutionId
    ) {
        return ResponseEntity.ok(runnerService.rejectExecution(user.id(), runnerExecutionId));
    }
}
