package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapScheduleStateJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapScheduleItemJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RoadmapScheduleRefreshService {
    private final RoadmapScheduleStateJpaRepository repository;
    private final RoadmapScheduleItemJpaRepository itemRepository;

    public RoadmapScheduleRefreshService(
            RoadmapScheduleStateJpaRepository repository,
            RoadmapScheduleItemJpaRepository itemRepository
    ) {
        this.repository = repository;
        this.itemRepository = itemRepository;
    }

    @Transactional
    public void request(String ownerId, Instant now) {
        repository.findCurrentByOwnerId(ownerId).ifPresent(state -> state.requestRefresh(now));
    }

    @Transactional
    public void markStarted(String ownerId, String userRoadmapNodeId, Instant now) {
        itemRepository.findByUserRoadmapNodeId(userRoadmapNodeId)
                .filter(item -> item.getOwnerId().equals(ownerId))
                .ifPresent(item -> item.start(now));
        request(ownerId, now);
    }

    @Transactional
    public void markCompleted(String ownerId, String userRoadmapNodeId, Instant now) {
        itemRepository.findByUserRoadmapNodeId(userRoadmapNodeId)
                .filter(item -> item.getOwnerId().equals(ownerId))
                .ifPresent(item -> item.complete(now));
        request(ownerId, now);
    }
}
