package com.moxiao.studypilot.learning.application;

import com.moxiao.studypilot.learning.api.CreateLearningPlanRequest;
import com.moxiao.studypilot.learning.domain.LearningGoalRepository;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanJpaRepository;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanVersionEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanVersionJpaRepository;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanVersionEntity;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class LearningPlanService {

    private final LearningGoalRepository goalRepository;
    private final LearningPlanJpaRepository planRepository;
    private final LearningPlanVersionJpaRepository versionRepository;

    public LearningPlanService(
            LearningGoalRepository goalRepository,
            LearningPlanJpaRepository planRepository,
            LearningPlanVersionJpaRepository versionRepository
    ) {
        this.goalRepository = goalRepository;
        this.planRepository = planRepository;
        this.versionRepository = versionRepository;
    }

    @Transactional
    public LearningPlanEntity create(String ownerId, CreateLearningPlanRequest request) {
        if (!goalRepository.existsByIdAndOwnerId(request.goalId(), ownerId)) {
            throw new ResourceNotFoundException("学习目标不存在");
        }
        Instant now = Instant.now();
        LearningPlanEntity plan = planRepository.save(new LearningPlanEntity(
                UUID.randomUUID().toString(),
                ownerId,
                request.goalId(),
                request.title().trim(),
                request.startDate(),
                request.endDate(),
                now
        ));
        saveVersion(plan, "创建计划草案", now);
        return plan;
    }

    @Transactional
    public LearningPlanEntity confirm(String ownerId, String planId) {
        LearningPlanEntity plan = requireOwnedPlan(ownerId, planId);
        Instant now = Instant.now();
        try {
            plan.confirm(now);
        } catch (IllegalStateException exception) {
            throw new IllegalArgumentException(exception.getMessage());
        }
        saveVersion(plan, "用户确认计划", now);
        return plan;
    }

    @Transactional(readOnly = true)
    public List<LearningPlanEntity> list(String ownerId) {
        return planRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    @Transactional(readOnly = true)
    public LearningPlanEntity requireOwnedPlan(String ownerId, String planId) {
        return planRepository.findByIdAndOwnerId(planId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("学习计划不存在"));
    }

    @Transactional(readOnly = true)
    public List<LearningPlanVersionEntity> versions(String ownerId, String planId) {
        requireOwnedPlan(ownerId, planId);
        return versionRepository.findAllByPlanIdOrderByVersionDesc(planId);
    }

    private void saveVersion(LearningPlanEntity plan, String reason, Instant now) {
        String snapshot = """
                {"id":"%s","goalId":"%s","title":"%s","status":"%s","version":%d}
                """.formatted(
                plan.getId(),
                plan.getGoalId(),
                plan.getTitle().replace("\"", "\\\""),
                plan.getStatus(),
                plan.getVersion()
        ).trim();
        versionRepository.save(new LearningPlanVersionEntity(
                plan.getId(),
                plan.getVersion(),
                snapshot,
                reason,
                now
        ));
    }
}
