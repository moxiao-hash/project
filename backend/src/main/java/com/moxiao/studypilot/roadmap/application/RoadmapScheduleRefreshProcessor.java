package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapScheduleStateJpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class RoadmapScheduleRefreshProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            RoadmapScheduleRefreshProcessor.class);
    private final RoadmapScheduleStateJpaRepository repository;
    private final RoadmapScheduleService scheduleService;

    public RoadmapScheduleRefreshProcessor(
            RoadmapScheduleStateJpaRepository repository,
            RoadmapScheduleService scheduleService
    ) {
        this.repository = repository;
        this.scheduleService = scheduleService;
    }

    @Scheduled(
            initialDelayString = "${studypilot.roadmap.schedule-refresh-delay-ms:5000}",
            fixedDelayString = "${studypilot.roadmap.schedule-refresh-delay-ms:5000}"
    )
    public void processRequested() {
        repository.findAllRequestedCurrent().stream()
                .map(schedule -> schedule.getOwnerId())
                .distinct()
                .forEach(ownerId -> {
                    try {
                        scheduleService.refresh(ownerId, null, null);
                    } catch (RuntimeException exception) {
                        LOGGER.warn("刷新用户路线日程失败: {}", ownerId, exception);
                    }
                });
    }
}
