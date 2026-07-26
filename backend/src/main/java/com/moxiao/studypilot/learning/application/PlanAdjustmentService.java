package com.moxiao.studypilot.learning.application;

import com.moxiao.studypilot.agent.api.UpdateAgentExecutionRequest;
import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.domain.ExecutionType;
import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionEntity;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionJpaRepository;
import com.moxiao.studypilot.auth.infrastructure.UserAccountJpaRepository;
import com.moxiao.studypilot.learning.api.CreatePlanAdjustmentRequest;
import com.moxiao.studypilot.learning.api.ExecutePlanAdjustmentRequest;
import com.moxiao.studypilot.learning.api.NightlyAdjustmentCandidateResponse;
import com.moxiao.studypilot.learning.domain.AdjustmentOperationType;
import com.moxiao.studypilot.learning.domain.LearningGoal;
import com.moxiao.studypilot.learning.domain.LearningPlanStatus;
import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import com.moxiao.studypilot.learning.domain.LearningGoalRepository;
import com.moxiao.studypilot.learning.domain.PlanAdjustmentStatus;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanJpaRepository;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanVersionEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanVersionJpaRepository;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskJpaRepository;
import com.moxiao.studypilot.learning.infrastructure.PlanAdjustmentEntity;
import com.moxiao.studypilot.learning.infrastructure.PlanAdjustmentJpaRepository;
import com.moxiao.studypilot.notification.api.CreateNotificationRequest;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.domain.NotificationType;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import com.moxiao.studypilot.user.infrastructure.UserSettingsJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class PlanAdjustmentService {

    private final PlanAdjustmentJpaRepository adjustmentRepository;
    private final LearningPlanJpaRepository planRepository;
    private final LearningTaskJpaRepository taskRepository;
    private final LearningPlanVersionJpaRepository versionRepository;
    private final LearningGoalRepository goalRepository;
    private final AgentExecutionJpaRepository executionRepository;
    private final AgentGovernanceService governanceService;
    private final NotificationService notificationService;
    private final UserAccountJpaRepository userRepository;
    private final UserSettingsJpaRepository settingsRepository;
    private final ObjectMapper objectMapper;

    public PlanAdjustmentService(
            PlanAdjustmentJpaRepository adjustmentRepository,
            LearningPlanJpaRepository planRepository,
            LearningTaskJpaRepository taskRepository,
            LearningPlanVersionJpaRepository versionRepository,
            LearningGoalRepository goalRepository,
            AgentExecutionJpaRepository executionRepository,
            AgentGovernanceService governanceService,
            NotificationService notificationService,
            UserAccountJpaRepository userRepository,
            UserSettingsJpaRepository settingsRepository,
            ObjectMapper objectMapper
    ) {
        this.adjustmentRepository = adjustmentRepository;
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.versionRepository = versionRepository;
        this.goalRepository = goalRepository;
        this.executionRepository = executionRepository;
        this.governanceService = governanceService;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PlanAdjustmentEntity create(CreatePlanAdjustmentRequest request) {
        PlanAdjustmentEntity existing = adjustmentRepository
                .findByOwnerIdAndIdempotencyKey(
                        request.ownerId(),
                        request.idempotencyKey()
                )
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        LearningPlanEntity plan = planRepository
                .findByIdAndOwnerId(request.planId(), request.ownerId())
                .filter(candidate -> candidate.getStatus() == LearningPlanStatus.CONFIRMED)
                .orElseThrow(() -> new ResourceNotFoundException("没有可调整的已确认计划"));
        validateOperations(request.operations());
        RiskLevel riskLevel = classifyRisk(request.operations(), plan.getEndDate());
        PlanAdjustmentStatus status = request.operations().isEmpty()
                ? PlanAdjustmentStatus.NO_CHANGE
                : PlanAdjustmentStatus.DRAFT_READY;
        Instant now = Instant.now();
        PlanAdjustmentEntity.CreatePlanAdjustmentRequestData data =
                new PlanAdjustmentEntity.CreatePlanAdjustmentRequestData(
                        request.ownerId(),
                        request.planId(),
                        request.idempotencyKey(),
                        request.analysisDate(),
                        request.triggerType(),
                        objectMapper.writeValueAsString(request.signals()),
                        request.summary().trim(),
                        objectMapper.writeValueAsString(request.operations()),
                        request.executionId()
                );
        return adjustmentRepository.save(new PlanAdjustmentEntity(
                UUID.randomUUID().toString(),
                data,
                riskLevel,
                status,
                plan.getVersion(),
                now
        ));
    }

    @Transactional(readOnly = true)
    public PlanAdjustmentEntity get(String adjustmentId) {
        return adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new ResourceNotFoundException("计划调整不存在"));
    }

    @Transactional(readOnly = true)
    public PlanAdjustmentEntity findByKey(String ownerId, String idempotencyKey) {
        return adjustmentRepository
                .findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey)
                .orElseThrow(() -> new ResourceNotFoundException("计划调整不存在"));
    }

    @Transactional(readOnly = true)
    public List<NightlyAdjustmentCandidateResponse> nightlyCandidates(Instant at) {
        return userRepository.findAll().stream()
                .filter(user -> planRepository
                        .findAllByOwnerIdOrderByCreatedAtDesc(user.getId())
                        .stream()
                        .anyMatch(plan -> plan.getStatus() == LearningPlanStatus.CONFIRMED))
                .map(user -> {
                    ZoneId zone = resolveTimeZone(user.getId());
                    LocalDate analysisDate = at.atZone(zone).toLocalDate().minusDays(1);
                    return new NightlyAdjustmentCandidateResponse(
                            user.getId(),
                            analysisDate
                    );
                })
                .filter(candidate -> adjustmentRepository
                        .findByOwnerIdAndIdempotencyKey(
                                candidate.ownerId(),
                                "plan-adjustment:nightly:"
                                        + candidate.ownerId()
                                        + ":"
                                        + candidate.analysisDate()
                        )
                        .isEmpty())
                .toList();
    }

    private ZoneId resolveTimeZone(String ownerId) {
        String configured = settingsRepository.findById(ownerId)
                .map(settings -> settings.getTimeZone())
                .orElse("Asia/Shanghai");
        try {
            return ZoneId.of(configured);
        } catch (DateTimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    /**
     * 在同一个数据库事务中执行整个调整草稿。先校验全部操作，再开始修改，
     * 从而保证某个任务版本冲突时不会产生部分成功。
     */
    @Transactional(noRollbackFor = ConflictException.class)
    public PlanAdjustmentEntity execute(
            String adjustmentId,
            ExecutePlanAdjustmentRequest request
    ) {
        PlanAdjustmentEntity adjustment = adjustmentRepository
                .findById(adjustmentId)
                .filter(candidate -> candidate.getOwnerId().equals(request.ownerId()))
                .orElseThrow(() -> new ResourceNotFoundException("计划调整不存在"));
        if (adjustment.getStatus() == PlanAdjustmentStatus.COMPLETED) {
            return adjustment;
        }

        AgentExecutionEntity execution = requireGovernedExecution(adjustment, request);
        LearningPlanEntity plan = planRepository
                .findByIdAndOwnerId(adjustment.getPlanId(), request.ownerId())
                .filter(candidate -> candidate.getStatus() == LearningPlanStatus.CONFIRMED)
                .orElseThrow(() -> new ResourceNotFoundException("没有可调整的已确认计划"));

        try {
            validatePlanVersion(adjustment, plan, request.expectedPlanVersion());
            List<CreatePlanAdjustmentRequest.Operation> operations =
                    readOperations(adjustment.getOperationsJson());
            Map<String, LearningTaskEntity> tasks =
                    validateAndCollectTasks(adjustment, plan, operations);
            LocalDate adjustedEndDate =
                    validateDatesAndResolveEndDate(adjustment, plan, operations);
            applyAdjustment(
                    adjustment,
                    execution,
                    plan,
                    tasks,
                    operations,
                    adjustedEndDate
            );
            return adjustment;
        } catch (ConflictException exception) {
            Instant now = Instant.now();
            adjustment.fail(exception.getMessage(), now);
            updateExecution(
                    execution.getId(),
                    ExecutionStatus.FAILED,
                    null,
                    exception.getMessage()
            );
            notificationService.create(new CreateNotificationRequest(
                    adjustment.getOwnerId(),
                    NotificationType.AGENT_FAILED,
                    "学习计划调整失败",
                    exception.getMessage()
            ));
            throw exception;
        }
    }

    private AgentExecutionEntity requireGovernedExecution(
            PlanAdjustmentEntity adjustment,
            ExecutePlanAdjustmentRequest request
    ) {
        if (!Objects.equals(adjustment.getExecutionId(), request.executionId())) {
            throw new ConflictException("执行记录与计划调整不匹配");
        }
        AgentExecutionEntity execution = executionRepository
                .findByIdAndOwnerId(request.executionId(), request.ownerId())
                .orElseThrow(() -> new ResourceNotFoundException("Agent 执行记录不存在"));
        AgentScope expectedScope = adjustment.getRiskLevel() == RiskLevel.LOW
                ? AgentScope.SMALL_PLAN_ADJUSTMENT
                : AgentScope.LARGE_PLAN_ADJUSTMENT;
        if (execution.getExecutionType() != ExecutionType.PLAN_ADJUSTMENT
                || execution.getRiskLevel() != adjustment.getRiskLevel()
                || execution.getRequiredScope() != expectedScope) {
            throw new ConflictException("Agent 治理记录与调整风险不匹配");
        }
        if (execution.getStatus() != ExecutionStatus.PENDING) {
            throw new ConflictException("计划调整尚未获得所需授权或确认");
        }
        return execution;
    }

    private void validatePlanVersion(
            PlanAdjustmentEntity adjustment,
            LearningPlanEntity plan,
            int expectedPlanVersion
    ) {
        if (expectedPlanVersion != adjustment.getBeforePlanVersion()
                || plan.getVersion() != expectedPlanVersion) {
            throw new ConflictException("学习计划版本已变化，请重新分析");
        }
    }

    private List<CreatePlanAdjustmentRequest.Operation> readOperations(String json) {
        CreatePlanAdjustmentRequest.Operation[] operations = objectMapper.readValue(
                json,
                CreatePlanAdjustmentRequest.Operation[].class
        );
        return List.of(operations);
    }

    private Map<String, LearningTaskEntity> validateAndCollectTasks(
            PlanAdjustmentEntity adjustment,
            LearningPlanEntity plan,
            List<CreatePlanAdjustmentRequest.Operation> operations
    ) {
        Map<String, LearningTaskEntity> tasks = new LinkedHashMap<>();
        for (CreatePlanAdjustmentRequest.Operation operation : operations) {
            if (tasks.containsKey(operation.taskId())) {
                throw new ConflictException("同一调整不能重复修改同一个任务");
            }
            LearningTaskEntity task = taskRepository
                    .findByIdAndOwnerId(operation.taskId(), adjustment.getOwnerId())
                    .filter(candidate -> candidate.getPlanId().equals(plan.getId()))
                    .orElseThrow(() -> new ConflictException("待调整任务不存在或不属于该计划"));
            if (task.getStatus() != LearningTaskStatus.TODO) {
                throw new ConflictException("只有待办任务可以由 Agent 调整");
            }
            if (task.getVersion() != operation.expectedVersion()) {
                throw new ConflictException("任务版本已变化，请重新分析");
            }
            tasks.put(task.getId(), task);
        }
        return tasks;
    }

    private LocalDate validateDatesAndResolveEndDate(
            PlanAdjustmentEntity adjustment,
            LearningPlanEntity plan,
            List<CreatePlanAdjustmentRequest.Operation> operations
    ) {
        LocalDate latestDate = plan.getEndDate();
        for (CreatePlanAdjustmentRequest.Operation operation : operations) {
            for (LocalDate date : List.of(
                    operation.scheduledDate() == null
                            ? plan.getStartDate()
                            : operation.scheduledDate(),
                    operation.secondScheduledDate() == null
                            ? plan.getStartDate()
                            : operation.secondScheduledDate()
            )) {
                if (date.isBefore(plan.getStartDate())) {
                    throw new ConflictException("调整后的任务日期不能早于计划开始日期");
                }
                if (date.isAfter(latestDate)) {
                    latestDate = date;
                }
            }
        }
        if (adjustment.getRiskLevel() == RiskLevel.LOW
                && latestDate.isAfter(plan.getEndDate())) {
            throw new ConflictException("小范围调整不能延长计划周期");
        }
        LearningGoal goal = goalRepository
                .findByIdAndOwnerId(plan.getGoalId(), adjustment.getOwnerId())
                .orElseThrow(() -> new ResourceNotFoundException("学习目标不存在"));
        if (latestDate.isAfter(goal.targetDate())) {
            throw new ConflictException("调整后的任务不能超过学习目标截止日期");
        }
        return latestDate;
    }

    private void applyAdjustment(
            PlanAdjustmentEntity adjustment,
            AgentExecutionEntity execution,
            LearningPlanEntity plan,
            Map<String, LearningTaskEntity> tasks,
            List<CreatePlanAdjustmentRequest.Operation> operations,
            LocalDate adjustedEndDate
    ) {
        Instant now = Instant.now();
        String beforeSnapshot = createSnapshot(
                plan,
                taskRepository.findAllByPlanIdOrderByScheduledDateAscCreatedAtAsc(plan.getId())
        );
        adjustment.startExecution(now);
        updateExecution(
                execution.getId(),
                ExecutionStatus.RUNNING,
                null,
                null
        );

        for (CreatePlanAdjustmentRequest.Operation operation : operations) {
            LearningTaskEntity task = tasks.get(operation.taskId());
            switch (operation.type()) {
                case RESCHEDULE_TASK ->
                        task.reschedule(operation.scheduledDate(), now);
                case UPDATE_ESTIMATE ->
                        task.updateEstimate(operation.estimatedMinutes(), now);
                case SPLIT_TASK -> {
                    task.replaceForSplit(
                            operation.firstTitle().trim(),
                            operation.firstEstimatedMinutes(),
                            now
                    );
                    taskRepository.save(new LearningTaskEntity(
                            UUID.randomUUID().toString(),
                            adjustment.getOwnerId(),
                            plan.getId(),
                            operation.secondTitle().trim(),
                            operation.secondScheduledDate(),
                            operation.secondEstimatedMinutes(),
                            now
                    ));
                }
            }
        }
        plan.applyAdjustment(adjustedEndDate, now);
        taskRepository.flush();
        String afterSnapshot = createSnapshot(
                plan,
                taskRepository.findAllByPlanIdOrderByScheduledDateAscCreatedAtAsc(plan.getId())
        );
        versionRepository.save(new LearningPlanVersionEntity(
                plan.getId(),
                plan.getVersion(),
                afterSnapshot,
                "Agent 自适应调整",
                now
        ));
        adjustment.complete(plan.getVersion(), beforeSnapshot, afterSnapshot, now);
        updateExecution(
                execution.getId(),
                ExecutionStatus.SUCCEEDED,
                "计划已按确认的调整草稿更新",
                null
        );
        notificationService.create(new CreateNotificationRequest(
                adjustment.getOwnerId(),
                NotificationType.PLAN_ADJUSTED,
                "学习计划已调整",
                adjustment.getSummary()
        ));
    }

    private void updateExecution(
            String executionId,
            ExecutionStatus status,
            String resultSummary,
            String errorMessage
    ) {
        governanceService.update(executionId, new UpdateAgentExecutionRequest(
                status,
                resultSummary,
                errorMessage,
                null,
                null,
                null,
                null,
                null
        ));
    }

    private String createSnapshot(
            LearningPlanEntity plan,
            List<LearningTaskEntity> tasks
    ) {
        List<TaskSnapshot> taskSnapshots = new ArrayList<>();
        tasks.stream()
                .sorted(Comparator.comparing(LearningTaskEntity::getScheduledDate)
                        .thenComparing(LearningTaskEntity::getId))
                .forEach(task -> taskSnapshots.add(new TaskSnapshot(
                        task.getId(),
                        task.getTitle(),
                        task.getScheduledDate(),
                        task.getEstimatedMinutes(),
                        task.getStatus(),
                        task.getVersion()
                )));
        return objectMapper.writeValueAsString(new PlanSnapshot(
                plan.getId(),
                plan.getGoalId(),
                plan.getTitle(),
                plan.getStartDate(),
                plan.getEndDate(),
                plan.getStatus(),
                plan.getVersion(),
                taskSnapshots
        ));
    }

    private RiskLevel classifyRisk(
            List<CreatePlanAdjustmentRequest.Operation> operations,
            LocalDate planEndDate
    ) {
        long splitCount = operations.stream()
                .filter(operation -> operation.type() == AdjustmentOperationType.SPLIT_TASK)
                .count();
        boolean outsidePlan = operations.stream()
                .flatMap(operation -> java.util.stream.Stream.of(
                        operation.scheduledDate(),
                        operation.secondScheduledDate()
                ))
                .filter(java.util.Objects::nonNull)
                .anyMatch(date -> date.isAfter(planEndDate));
        return operations.size() > 3 || splitCount > 1 || outsidePlan
                ? RiskLevel.HIGH
                : RiskLevel.LOW;
    }

    private void validateOperations(
            List<CreatePlanAdjustmentRequest.Operation> operations
    ) {
        for (CreatePlanAdjustmentRequest.Operation operation : operations) {
            if (operation.type() == AdjustmentOperationType.RESCHEDULE_TASK
                    && operation.scheduledDate() == null) {
                throw new IllegalArgumentException("重新安排任务必须提供日期");
            }
            if (operation.type() == AdjustmentOperationType.UPDATE_ESTIMATE
                    && operation.estimatedMinutes() == null) {
                throw new IllegalArgumentException("修改预计时长必须提供分钟数");
            }
            if (operation.type() == AdjustmentOperationType.SPLIT_TASK
                    && (operation.firstTitle() == null
                    || operation.firstEstimatedMinutes() == null
                    || operation.secondTitle() == null
                    || operation.secondScheduledDate() == null
                    || operation.secondEstimatedMinutes() == null)) {
                throw new IllegalArgumentException("拆分任务必须完整提供两个部分");
            }
        }
    }

    private record PlanSnapshot(
            String id,
            String goalId,
            String title,
            LocalDate startDate,
            LocalDate endDate,
            LearningPlanStatus status,
            int version,
            List<TaskSnapshot> tasks
    ) {
    }

    private record TaskSnapshot(
            String id,
            String title,
            LocalDate scheduledDate,
            int estimatedMinutes,
            LearningTaskStatus status,
            int version
    ) {
    }
}
