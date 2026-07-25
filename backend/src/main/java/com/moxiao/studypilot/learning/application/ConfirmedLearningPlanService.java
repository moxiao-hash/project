package com.moxiao.studypilot.learning.application;

import com.moxiao.studypilot.learning.api.ConfirmedLearningPlanResponse;
import com.moxiao.studypilot.learning.api.CreateConfirmedLearningPlanRequest;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanJpaRepository;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConfirmedLearningPlanService {

    private final LearningPlanService planService;
    private final LearningTaskService taskService;
    private final LearningPlanJpaRepository planRepository;
    private final LearningTaskJpaRepository taskRepository;

    public ConfirmedLearningPlanService(
            LearningPlanService planService,
            LearningTaskService taskService,
            LearningPlanJpaRepository planRepository,
            LearningTaskJpaRepository taskRepository
    ) {
        this.planService = planService;
        this.taskService = taskService;
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public ConfirmedLearningPlanResponse create(CreateConfirmedLearningPlanRequest request) {
        return planRepository.findByOwnerIdAndGenerationIdempotencyKey(
                        request.ownerId(),
                        request.idempotencyKey()
                )
                .map(this::existingResponse)
                .orElseGet(() -> createNew(request));
    }

    private ConfirmedLearningPlanResponse createNew(
            CreateConfirmedLearningPlanRequest request
    ) {
        LearningPlanEntity plan = planService.createGenerated(
                request.ownerId(),
                request.toPlanRequest(),
                request.idempotencyKey()
        );
        planService.confirm(request.ownerId(), plan.getId());
        List<LearningTaskEntity> tasks = request.tasks().stream()
                .map(task -> taskService.create(
                        request.ownerId(),
                        plan.getId(),
                        task.toTaskRequest()
                ))
                .toList();
        return ConfirmedLearningPlanResponse.from(plan, tasks);
    }

    private ConfirmedLearningPlanResponse existingResponse(LearningPlanEntity plan) {
        return ConfirmedLearningPlanResponse.from(
                plan,
                taskRepository.findAllByPlanIdOrderByScheduledDateAscCreatedAtAsc(plan.getId())
        );
    }
}

