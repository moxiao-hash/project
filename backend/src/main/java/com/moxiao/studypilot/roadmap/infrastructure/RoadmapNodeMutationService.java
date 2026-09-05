package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;
import com.moxiao.studypilot.roadmap.application.RoadmapEnrollmentService;
import com.moxiao.studypilot.roadmap.application.RoadmapScheduleRefreshService;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Instant;

/**
 * Coordinates node completion with one mandatory lock order:
 * user roadmap first, user node second, availability recalculation last.
 *
 * <p>Keeping the entity completion transition package-private makes this
 * coordinator the only production entry point that can complete a roadmap
 * node. Later check-in and quiz workflows must supply their evidence before
 * calling this method; they cannot load and complete the node independently.
 */
@Service
public class RoadmapNodeMutationService {

    private final UserRoadmapJpaRepository userRoadmapRepository;
    private final UserRoadmapNodeJpaRepository userNodeRepository;
    private final RoadmapEnrollmentService enrollmentService;
    private final RoadmapScheduleRefreshService scheduleRefreshService;

    public RoadmapNodeMutationService(
            UserRoadmapJpaRepository userRoadmapRepository,
            UserRoadmapNodeJpaRepository userNodeRepository,
            RoadmapEnrollmentService enrollmentService,
            RoadmapScheduleRefreshService scheduleRefreshService
    ) {
        this.userRoadmapRepository = userRoadmapRepository;
        this.userNodeRepository = userNodeRepository;
        this.enrollmentService = enrollmentService;
        this.scheduleRefreshService = scheduleRefreshService;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void completeEligibleNode(String enrollmentId, String nodeId) {
        if (!TransactionAspectSupport.currentTransactionStatus().isNewTransaction()) {
            throw new IllegalStateException("路线节点变更必须由锁优先事务直接发起");
        }
        // This must remain the first database operation in the transaction.
        UserRoadmapEntity enrollment = userRoadmapRepository.findByIdForUpdate(enrollmentId)
                .orElseThrow(() -> new ResourceNotFoundException("学习路线绑定不存在"));
        UserRoadmapNodeEntity state = userNodeRepository
                .findByUserRoadmapIdAndNodeId(enrollmentId, nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("路线节点状态不存在"));

        state.completeAfterRequirements(Instant.now());
        enrollmentService.recalculateAvailability(enrollmentId);
        scheduleRefreshService.markCompleted(
                enrollment.getOwnerId(), state.getId(), Instant.now());
    }

    /**
     * Records a node quiz result while preserving the enrollment -> node lock order.
     * The immutable quiz binding is preferred; the current lookup is retained only
     * for quizzes created before the binding migration.
     */
    @Transactional
    public void recordNodeQuizResult(QuizEntity quiz, double score, Instant now) {
        if (quiz.getUserRoadmapId() == null || quiz.getUserRoadmapNodeId() == null) {
            throw new IllegalStateException("节点测验缺少不可变路线绑定");
        }
        UserRoadmapEntity enrollment = userRoadmapRepository
                .findByIdForUpdate(quiz.getUserRoadmapId())
                .orElseThrow(() -> new ResourceNotFoundException("学习路线绑定不存在"));
        UserRoadmapNodeEntity state = userNodeRepository
                .findByIdForUpdate(quiz.getUserRoadmapNodeId())
                .orElseThrow(() -> new ResourceNotFoundException("路线节点状态不存在"));
        if (!state.getUserRoadmapId().equals(enrollment.getId())
                || !state.getNodeId().equals(quiz.getRoadmapNodeId())) {
            throw new IllegalStateException("节点测验路线绑定不一致");
        }
        state.recordQuizResult(score, now);
        if (score >= 70 && state.completionRequirementsSatisfied()) {
            state.completeAfterRequirements(now);
            enrollmentService.recalculateAvailability(enrollment.getId());
            scheduleRefreshService.markCompleted(enrollment.getOwnerId(), state.getId(), now);
        } else {
            scheduleRefreshService.request(enrollment.getOwnerId(), now);
        }
    }

    @Transactional
    public void recordArtifactAccepted(String ownerId, String nodeId, Instant now) {
        UserRoadmapEntity enrollment = userRoadmapRepository
                .findByOwnerIdAndActiveSlotForUpdate(ownerId, "CURRENT")
                .or(() -> userRoadmapRepository.findByIdForUpdate(ownerId))
                .orElseThrow(() -> new ResourceNotFoundException("学习路线绑定不存在"));
        UserRoadmapNodeEntity state = userNodeRepository
                .findByUserRoadmapIdAndNodeId(enrollment.getId(), nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("路线节点状态不存在"));
        state.acceptArtifact(now);
        if (state.completionRequirementsSatisfied()) {
            state.completeAfterRequirements(now);
            enrollmentService.recalculateAvailability(enrollment.getId());
            scheduleRefreshService.markCompleted(enrollment.getOwnerId(), state.getId(), now);
        } else {
            scheduleRefreshService.request(enrollment.getOwnerId(), now);
        }
    }

}
