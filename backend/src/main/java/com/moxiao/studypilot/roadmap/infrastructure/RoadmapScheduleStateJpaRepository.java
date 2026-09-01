package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface RoadmapScheduleStateJpaRepository
        extends JpaRepository<RoadmapScheduleStateEntity, String> {
    Optional<RoadmapScheduleStateEntity> findByOwnerIdAndUserRoadmapId(
            String ownerId, String userRoadmapId);

    List<RoadmapScheduleStateEntity> findAllByOwnerId(String ownerId);

    @Query("""
            SELECT schedule FROM RoadmapScheduleStateEntity schedule,
              UserRoadmapEntity roadmap
            WHERE schedule.userRoadmapId = roadmap.id
              AND schedule.ownerId = :ownerId AND roadmap.activeSlot = 'CURRENT'
            """)
    Optional<RoadmapScheduleStateEntity> findCurrentByOwnerId(@Param("ownerId") String ownerId);

    @Query("""
            SELECT schedule FROM RoadmapScheduleStateEntity schedule,
              UserRoadmapEntity roadmap
            WHERE schedule.userRoadmapId = roadmap.id
              AND roadmap.activeSlot = 'CURRENT'
              AND schedule.refreshRequestedAt IS NOT NULL
            """)
    List<RoadmapScheduleStateEntity> findAllRequestedCurrent();
}
