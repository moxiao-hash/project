package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapScheduleStateJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RoadmapScheduleRefreshService {
    private final RoadmapScheduleStateJpaRepository repository;

    public RoadmapScheduleRefreshService(RoadmapScheduleStateJpaRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void request(String ownerId, Instant now) {
        repository.findByOwnerId(ownerId).ifPresent(state -> state.requestRefresh(now));
    }
}
