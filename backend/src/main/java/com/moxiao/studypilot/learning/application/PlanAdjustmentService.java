package com.moxiao.studypilot.learning.application;

import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.learning.api.CreatePlanAdjustmentRequest;
import com.moxiao.studypilot.learning.domain.AdjustmentOperationType;
import com.moxiao.studypilot.learning.domain.LearningPlanStatus;
import com.moxiao.studypilot.learning.domain.PlanAdjustmentStatus;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanJpaRepository;
import com.moxiao.studypilot.learning.infrastructure.PlanAdjustmentEntity;
import com.moxiao.studypilot.learning.infrastructure.PlanAdjustmentJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class PlanAdjustmentService {

    private final PlanAdjustmentJpaRepository adjustmentRepository;
    private final LearningPlanJpaRepository planRepository;
    private final ObjectMapper objectMapper;

    public PlanAdjustmentService(
            PlanAdjustmentJpaRepository adjustmentRepository,
            LearningPlanJpaRepository planRepository,
            ObjectMapper objectMapper
    ) {
        this.adjustmentRepository = adjustmentRepository;
        this.planRepository = planRepository;
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
                        objectMapper.writeValueAsString(request.operations())
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
}
