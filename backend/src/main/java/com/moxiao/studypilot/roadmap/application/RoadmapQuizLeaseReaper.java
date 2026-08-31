package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapQuizGenerationJobJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;

@Service
public class RoadmapQuizLeaseReaper {
    private static final int REAP_BATCH_SIZE = 100;
    private final RoadmapQuizGenerationJobJpaRepository jobRepository;
    private final RoadmapQuizLeaseExpiryService expiryService;

    public RoadmapQuizLeaseReaper(
            RoadmapQuizGenerationJobJpaRepository jobRepository,
            RoadmapQuizLeaseExpiryService expiryService
    ) {
        this.jobRepository = jobRepository;
        this.expiryService = expiryService;
    }

    @Transactional(readOnly = true)
    public void reapExhausted(Instant now) {
        for (String jobId : jobRepository.findExpiredExhaustedIds(
                now, PageRequest.of(0, REAP_BATCH_SIZE))) {
            expiryService.expireOne(jobId, now);
        }
    }
}
