package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapQuizGenerationJobJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RoadmapQuizLeaseExpiryService {
    private final UserRoadmapJpaRepository roadmapRepository;
    private final UserRoadmapNodeJpaRepository stateRepository;
    private final RoadmapQuizGenerationJobJpaRepository jobRepository;

    public RoadmapQuizLeaseExpiryService(
            UserRoadmapJpaRepository roadmapRepository,
            UserRoadmapNodeJpaRepository stateRepository,
            RoadmapQuizGenerationJobJpaRepository jobRepository
    ) {
        this.roadmapRepository = roadmapRepository;
        this.stateRepository = stateRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireOne(String jobId, Instant now) {
        roadmapRepository.findBoundRoadmapForQuizJobForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("路线报名不存在"));
        var state = stateRepository.findBoundStateForQuizJobForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("路线节点状态不存在"));
        var job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("路线测验生成任务不存在"));
        if (job.expireExhaustedLease(now)) {
            state.markQuizGenerationFailed(now);
        }
    }
}
