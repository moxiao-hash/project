package com.moxiao.studypilot.assessment.application;

import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.domain.TriggerType;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.MasteryJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;
import com.moxiao.studypilot.learning.api.CreatePlanAdjustmentRequest;
import com.moxiao.studypilot.learning.api.ExecutePlanAdjustmentRequest;
import com.moxiao.studypilot.learning.application.PlanAdjustmentService;
import com.moxiao.studypilot.learning.domain.AdjustmentOperationType;
import com.moxiao.studypilot.learning.domain.LearningTaskKind;
import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanJpaRepository;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskJpaRepository;
import com.moxiao.studypilot.user.infrastructure.UserSettingsJpaRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 把低掌握度转成“候选计划调整”，不绕过阶段 5 的授权治理。
 */
@Service
public class ReviewTaskCandidateService {
    private final MasteryJpaRepository masteryRepository;
    private final LearningTaskJpaRepository taskRepository;
    private final LearningPlanJpaRepository planRepository;
    private final UserSettingsJpaRepository settingsRepository;
    private final PlanAdjustmentService adjustmentService;
    private final AgentExecutionJpaRepository executionRepository;

    public ReviewTaskCandidateService(
            MasteryJpaRepository masteryRepository,
            LearningTaskJpaRepository taskRepository,
            LearningPlanJpaRepository planRepository,
            UserSettingsJpaRepository settingsRepository,
            PlanAdjustmentService adjustmentService,
            AgentExecutionJpaRepository executionRepository
    ) {
        this.masteryRepository = masteryRepository;
        this.taskRepository = taskRepository;
        this.planRepository = planRepository;
        this.settingsRepository = settingsRepository;
        this.adjustmentService = adjustmentService;
        this.executionRepository = executionRepository;
    }

    public void createCandidates(
            QuizEntity quiz,
            String attemptId,
            Set<String> weakCodingPoints
    ) {
        if (quiz.getTaskId() == null) {
            return;
        }
        LearningTaskEntity sourceTask = taskRepository
                .findByIdAndOwnerId(quiz.getTaskId(), quiz.getOwnerId())
                .orElse(null);
        if (sourceTask == null) {
            return;
        }
        var plan = planRepository.findByIdAndOwnerId(
                sourceTask.getPlanId(), quiz.getOwnerId()
        ).orElse(null);
        if (plan == null) {
            return;
        }
        masteryRepository.findAllByOwnerIdOrderByScoreAsc(quiz.getOwnerId()).stream()
                .filter(mastery -> mastery.getScore() < 70)
                .filter(mastery -> !taskRepository
                        .existsByOwnerIdAndKnowledgePointAndStatusIn(
                                quiz.getOwnerId(),
                                mastery.getKnowledgePoint(),
                                List.of(LearningTaskStatus.TODO, LearningTaskStatus.DEFERRED)
                        ))
                .limit(2)
                .forEach(mastery -> {
                    boolean coding = weakCodingPoints.contains(mastery.getKnowledgePoint());
                    int minutes = coding ? 45 : 30;
                    LearningTaskKind kind = coding
                            ? LearningTaskKind.CODING_PRACTICE
                            : LearningTaskKind.REVIEW;
                    LocalDate date = firstAvailableDate(
                            quiz.getOwnerId(), plan.getStartDate(), plan.getEndDate(), minutes
                    );
                    String key = "review:" + attemptId + ":" + mastery.getKnowledgePoint();
                    var adjustment = adjustmentService.create(
                            new CreatePlanAdjustmentRequest(
                                    quiz.getOwnerId(),
                                    plan.getId(),
                                    key,
                                    LocalDate.now(),
                                    TriggerType.USER_REQUEST,
                                    List.of(),
                                    (coding ? "编程练习：" : "复习：")
                                            + mastery.getKnowledgePoint(),
                                    null,
                                    List.of(new CreatePlanAdjustmentRequest.Operation(
                                            AdjustmentOperationType.INSERT_REVIEW_TASK,
                                            null, null, date, minutes,
                                            null, null, null, null, null,
                                            (coding ? "编程练习：" : "复习：")
                                                    + mastery.getKnowledgePoint(),
                                            kind,
                                            mastery.getKnowledgePoint(),
                                            attemptId
                                    ))
                            )
                    );
                    executionRepository.findById(adjustment.getExecutionId())
                            .filter(execution -> execution.getStatus() == ExecutionStatus.PENDING)
                            .ifPresent(execution -> adjustmentService.execute(
                                    adjustment.getId(),
                                    new ExecutePlanAdjustmentRequest(
                                            quiz.getOwnerId(),
                                            execution.getId(),
                                            adjustment.getBeforePlanVersion()
                                    )
                            ));
                });
    }

    private LocalDate firstAvailableDate(
            String ownerId,
            LocalDate planStart,
            LocalDate planEnd,
            int minutes
    ) {
        int dailyLimit = settingsRepository.findById(ownerId)
                .map(settings -> settings.getDailyStudyLimitMinutes())
                .orElse(120);
        LocalDate date = LocalDate.now().plusDays(1).isAfter(planStart)
                ? LocalDate.now().plusDays(1) : planStart;
        List<LearningTaskEntity> tasks =
                taskRepository.findAllByOwnerIdOrderByScheduledDateAscCreatedAtAsc(ownerId);
        for (LocalDate candidate = date; !candidate.isAfter(planEnd);
             candidate = candidate.plusDays(1)) {
            LocalDate selected = candidate;
            int used = tasks.stream()
                    .filter(task -> task.getScheduledDate().equals(selected))
                    .mapToInt(LearningTaskEntity::getEstimatedMinutes)
                    .sum();
            if (used + minutes <= dailyLimit) {
                return candidate;
            }
        }
        // 没有容量时仍生成候选；风险分类会把周期外操作升级为 HIGH。
        return planEnd.plusDays(1);
    }
}
