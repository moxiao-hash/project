package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.learning.application.LearningTaskService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/learning-tasks")
public class LearningTaskController {

    private final LearningTaskService taskService;

    public LearningTaskController(LearningTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public List<LearningTaskResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return taskService.list(user.id(), date).stream()
                .map(LearningTaskResponse::from)
                .toList();
    }

    @PatchMapping("/{taskId}/status")
    public LearningTaskResponse changeStatus(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String taskId,
            @Valid @RequestBody ChangeTaskStatusRequest request
    ) {
        return LearningTaskResponse.from(taskService.changeStatus(user.id(), taskId, request));
    }

    @GetMapping("/{taskId}/history")
    public List<TaskChangeResponse> history(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String taskId
    ) {
        return taskService.history(user.id(), taskId).stream()
                .map(TaskChangeResponse::from)
                .toList();
    }
}
