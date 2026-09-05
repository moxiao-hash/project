package com.moxiao.studypilot.agent.runner;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
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

    @PostMapping("/executions/{executionId}/confirm")
    public ResponseEntity<RunnerExecutionResult> confirm(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String executionId
    ) {
        return ResponseEntity.ok(runnerService.confirmExecution(user.id(), executionId));
    }

    @PostMapping("/executions/{executionId}/reject")
    public ResponseEntity<RunnerExecutionResult> reject(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String executionId
    ) {
        return ResponseEntity.ok(runnerService.rejectExecution(user.id(), executionId));
    }
}
