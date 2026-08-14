package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapQuizGenerationJobEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapQuizGenerationJobJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RoadmapQuizLeaseReaper {
    private final RoadmapQuizGenerationJobJpaRepository jobRepository;
    private final UserRoadmapNodeJpaRepository stateRepository;

    public RoadmapQuizLeaseReaper(
            RoadmapQuizGenerationJobJpaRepository jobRepository,
            UserRoadmapNodeJpaRepository stateRepository
    ) {
        this.jobRepository = jobRepository;
        this.stateRepository = stateRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reapExhausted(Instant now) {
        for (RoadmapQuizGenerationJobEntity expired : jobRepository.findExpiredExhausted()) {
            if (expired.expireExhaustedLease(now)) {
                stateRepository.findById(expired.getUserRoadmapNodeId())
                        .orElseThrow(() -> new ResourceNotFoundException("路线节点状态不存在"))
                        .markQuizGenerationFailed(now);
            }
        }
    }
}
