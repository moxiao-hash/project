package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.learning.application.LearningPlanService;
import com.moxiao.studypilot.learning.application.LearningTaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/learning-plans")
public class LearningPlanController {

    private final LearningPlanService planService;
    private final LearningTaskService taskService;

    public LearningPlanController(
            LearningPlanService planService,
            LearningTaskService taskService
    ) {
        this.planService = planService;
        this.taskService = taskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LearningPlanResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateLearningPlanRequest request
    ) {
        return LearningPlanResponse.from(planService.create(user.id(), request));
    }

    @GetMapping
    public List<LearningPlanResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return planService.list(user.id()).stream().map(LearningPlanResponse::from).toList();
    }

    @PostMapping("/{planId}/confirm")
    public LearningPlanResponse confirm(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String planId
    ) {
        return LearningPlanResponse.from(planService.confirm(user.id(), planId));
    }

    @PostMapping("/{planId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public LearningTaskResponse createTask(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String planId,
            @Valid @RequestBody CreateLearningTaskRequest request
    ) {
        return LearningTaskResponse.from(taskService.create(user.id(), planId, request));
    }

    @GetMapping("/{planId}/versions")
    public List<LearningPlanVersionResponse> versions(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String planId
    ) {
        return planService.versions(user.id(), planId).stream()
                .map(LearningPlanVersionResponse::from)
                .toList();
    }
}
