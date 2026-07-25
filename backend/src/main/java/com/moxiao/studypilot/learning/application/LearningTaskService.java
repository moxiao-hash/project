package com.moxiao.studypilot.learning.application;

import com.moxiao.studypilot.learning.api.ChangeTaskStatusRequest;
import com.moxiao.studypilot.learning.api.CreateLearningTaskRequest;
import com.moxiao.studypilot.learning.domain.LearningPlanStatus;
import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskJpaRepository;
import com.moxiao.studypilot.learning.infrastructure.TaskChangeEntity;
import com.moxiao.studypilot.learning.infrastructure.TaskChangeJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class LearningTaskService {

    private final LearningPlanService planService;
    private final LearningTaskJpaRepository taskRepository;
    private final TaskChangeJpaRepository changeRepository;

    public LearningTaskService(
            LearningPlanService planService,
            LearningTaskJpaRepository taskRepository,
            TaskChangeJpaRepository changeRepository
    ) {
        this.planService = planService;
        this.taskRepository = taskRepository;
        this.changeRepository = changeRepository;
    }

    @Transactional
    public LearningTaskEntity create(
            String ownerId,
            String planId,
            CreateLearningTaskRequest request
    ) {
        LearningPlanEntity plan = planService.requireOwnedPlan(ownerId, planId);
        if (plan.getStatus() != LearningPlanStatus.CONFIRMED) {
            throw new ConflictException("计划确认后才能创建正式任务");
        }
        Instant now = Instant.now();
        return taskRepository.save(new LearningTaskEntity(
                UUID.randomUUID().toString(),
                ownerId,
                planId,
                request.title().trim(),
                request.scheduledDate(),
                request.estimatedMinutes(),
                now
        ));
    }

    @Transactional
    public LearningTaskEntity changeStatus(
            String ownerId,
            String taskId,
            ChangeTaskStatusRequest request
    ) {
        LearningTaskEntity task = requireOwnedTask(ownerId, taskId);
        Instant now = Instant.now();
        LearningTaskStatus previous = task.changeStatus(request.status(), now);
        changeRepository.save(new TaskChangeEntity(
                taskId,
                previous,
                request.status(),
                request.reason(),
                now
        ));
        return task;
    }

    @Transactional(readOnly = true)
    public List<LearningTaskEntity> list(String ownerId, LocalDate date) {
        if (date == null) {
            return taskRepository.findAllByOwnerIdOrderByScheduledDateAscCreatedAtAsc(ownerId);
        }
        return taskRepository.findAllByOwnerIdAndScheduledDateOrderByCreatedAt(ownerId, date);
    }

    @Transactional(readOnly = true)
    public List<TaskChangeEntity> history(String ownerId, String taskId) {
        requireOwnedTask(ownerId, taskId);
        return changeRepository.findAllByTaskIdOrderByCreatedAtDesc(taskId);
    }

    private LearningTaskEntity requireOwnedTask(String ownerId, String taskId) {
        return taskRepository.findByIdAndOwnerId(taskId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("学习任务不存在"));
    }
}
