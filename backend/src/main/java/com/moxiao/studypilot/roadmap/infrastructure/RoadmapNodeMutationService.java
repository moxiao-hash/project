package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.application.RoadmapEnrollmentService;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

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

    public RoadmapNodeMutationService(
            UserRoadmapJpaRepository userRoadmapRepository,
            UserRoadmapNodeJpaRepository userNodeRepository,
            RoadmapEnrollmentService enrollmentService
    ) {
        this.userRoadmapRepository = userRoadmapRepository;
        this.userNodeRepository = userNodeRepository;
        this.enrollmentService = enrollmentService;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void completeEligibleNode(String enrollmentId, String nodeId) {
        // This must remain the first database operation in the transaction.
        userRoadmapRepository.findByIdForUpdate(enrollmentId)
                .orElseThrow(() -> new ResourceNotFoundException("学习路线绑定不存在"));
        UserRoadmapNodeEntity state = userNodeRepository
                .findByUserRoadmapIdAndNodeId(enrollmentId, nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("路线节点状态不存在"));

        state.completeAfterRequirements(Instant.now());
        enrollmentService.recalculateAvailability(enrollmentId);
    }
}
