package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.application.InternalLearningContextService;
import com.moxiao.studypilot.learning.application.LearningPlanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class InternalLearningToolController {

    private final InternalLearningContextService contextService;
    private final LearningPlanService planService;

    public InternalLearningToolController(
            InternalLearningContextService contextService,
            LearningPlanService planService
    ) {
        this.contextService = contextService;
        this.planService = planService;
    }

    @GetMapping("/users/{ownerId}/learning-context")
    public InternalLearningContextResponse context(@PathVariable String ownerId) {
        return contextService.get(ownerId);
    }

    @PostMapping("/learning-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public LearningPlanResponse createPlanDraft(
            @Valid @RequestBody InternalCreateLearningPlanRequest request
    ) {
        return LearningPlanResponse.from(
                planService.create(request.ownerId(), request.toPlanRequest())
        );
    }
}
