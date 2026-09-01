package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "roadmap_schedule_states")
public class RoadmapScheduleStateEntity {
    @Id
    @Column(nullable = false, length = 36)
    private String id;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(name = "user_roadmap_id", nullable = false, length = 36)
    private String userRoadmapId;
    @Column(name = "time_zone", nullable = false, length = 50)
    private String timeZone;
    @Column(name = "daily_capacity_minutes", nullable = false)
    private int dailyCapacityMinutes;
    @Column(name = "weekends_enabled", nullable = false)
    private boolean weekendsEnabled;
    @Column(name = "refreshed_at", nullable = false)
    private Instant refreshedAt;
    @Column(name = "refresh_requested_at")
    private Instant refreshRequestedAt;

    protected RoadmapScheduleStateEntity() { }

    public RoadmapScheduleStateEntity(
            String id, String ownerId, String userRoadmapId, String timeZone,
            int dailyCapacityMinutes, boolean weekendsEnabled, Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.userRoadmapId = userRoadmapId;
        markRefreshed(userRoadmapId, timeZone, dailyCapacityMinutes, weekendsEnabled, now);
    }

    public void markRefreshed(
            String userRoadmapId, String timeZone, int dailyCapacityMinutes,
            boolean weekendsEnabled, Instant now
    ) {
        this.userRoadmapId = userRoadmapId;
        this.timeZone = timeZone;
        this.dailyCapacityMinutes = dailyCapacityMinutes;
        this.weekendsEnabled = weekendsEnabled;
        this.refreshedAt = now;
        this.refreshRequestedAt = null;
    }

    public void requestRefresh(Instant now) {
        if (refreshRequestedAt == null || !refreshRequestedAt.isAfter(refreshedAt)) {
            refreshRequestedAt = now;
        }
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getUserRoadmapId() { return userRoadmapId; }
    public String getTimeZone() { return timeZone; }
    public int getDailyCapacityMinutes() { return dailyCapacityMinutes; }
    public boolean isWeekendsEnabled() { return weekendsEnabled; }
    public Instant getRefreshedAt() { return refreshedAt; }
    public Instant getRefreshRequestedAt() { return refreshRequestedAt; }
}
