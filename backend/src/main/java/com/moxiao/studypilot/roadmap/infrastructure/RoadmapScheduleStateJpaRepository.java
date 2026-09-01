package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoadmapScheduleStateJpaRepository
        extends JpaRepository<RoadmapScheduleStateEntity, String> {
    Optional<RoadmapScheduleStateEntity> findByOwnerId(String ownerId);
}
