package com.moxiao.studypilot.learning.application;

import com.moxiao.studypilot.learning.api.ChangeTaskStatusRequest;
import com.moxiao.studypilot.learning.api.CreateLearningTaskRequest;
import com.moxiao.studypilot.learning.api.InternalChangeTaskStatusRequest;
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
import java.util.Objects;
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
        return applyStatusChange(task, request, null);
    }

    /**
     * 执行来自 Agent 的幂等任务状态操作。
     *
     * <p>先校验任务归属，再查找幂等记录。重复的同一请求直接返回当前任务，不再次
     * 增加版本和历史；相同幂等键若对应不同动作则视为冲突。</p>
     */
    @Transactional
    public LearningTaskEntity changeStatusIdempotently(
            String taskId,
            InternalChangeTaskStatusRequest request
    ) {
        LearningTaskEntity task = requireOwnedTask(request.ownerId(), taskId);
        TaskChangeEntity existing = changeRepository
                .findByOperationIdempotencyKey(request.idempotencyKey())
                .orElse(null);
        if (existing != null) {
            if (!sameOperation(existing, taskId, request)) {
                throw new ConflictException("幂等键已被其他任务操作使用");
            }
            return task;
        }
        if (task.getVersion() != request.expectedVersion()) {
            throw new ConflictException(
                    "任务版本已变化，请刷新任务后重新确认操作"
            );
        }
        return applyStatusChange(
                task,
                request.toStatusRequest(),
                request.idempotencyKey()
        );
    }

    private LearningTaskEntity applyStatusChange(
            LearningTaskEntity task,
            ChangeTaskStatusRequest request,
            String operationIdempotencyKey
    ) {
        Instant now = Instant.now();
        LocalDate previousDate = task.getScheduledDate();
        LearningTaskStatus previous = task.changeStatus(
                request.status(),
                request.scheduledDate(),
                now
        );
        changeRepository.save(new TaskChangeEntity(
                task.getId(),
                previous,
                request.status(),
                previousDate,
                task.getScheduledDate(),
                request.reason(),
                operationIdempotencyKey,
                now
        ));
        return task;
    }

    private boolean sameOperation(
            TaskChangeEntity existing,
            String taskId,
            InternalChangeTaskStatusRequest request
    ) {
        boolean sameTargetDate = request.status() != LearningTaskStatus.DEFERRED
                || Objects.equals(
                existing.getToScheduledDate(),
                request.scheduledDate()
        );
        return existing.getTaskId().equals(taskId)
                && existing.getToStatus() == request.status()
                && sameTargetDate
                && Objects.equals(existing.getReason(), request.reason());
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
