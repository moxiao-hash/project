package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.RoadmapScheduleItemStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "roadmap_schedule_items")
public class RoadmapScheduleItemEntity {
    @Id
    @Column(nullable = false, length = 36)
    private String id;
    @Column(name = "schedule_id", nullable = false, length = 36)
    private String scheduleId;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(name = "user_roadmap_id", nullable = false, length = 36)
    private String userRoadmapId;
    @Column(name = "user_roadmap_node_id", nullable = false, length = 36)
    private String userRoadmapNodeId;
    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;
    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;
    @Column(name = "planned_minutes", nullable = false)
    private int plannedMinutes;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoadmapScheduleItemStatus status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RoadmapScheduleItemEntity() { }

    public RoadmapScheduleItemEntity(
            String id, String scheduleId, String ownerId, String userRoadmapId,
            String userRoadmapNodeId, String nodeId, LocalDate scheduledDate,
            int plannedMinutes, Instant now
    ) {
        this.id = id;
        this.scheduleId = scheduleId;
        this.ownerId = ownerId;
        this.userRoadmapId = userRoadmapId;
        this.userRoadmapNodeId = userRoadmapNodeId;
        this.nodeId = nodeId;
        this.scheduledDate = scheduledDate;
        this.plannedMinutes = plannedMinutes;
        this.status = RoadmapScheduleItemStatus.PLANNED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void reschedule(LocalDate date, int minutes, Instant now) {
        if (status != RoadmapScheduleItemStatus.PLANNED) {
            return;
        }
        scheduledDate = date;
        plannedMinutes = minutes;
        updatedAt = now;
    }

    public void start(Instant now) {
        if (status == RoadmapScheduleItemStatus.PLANNED) {
            status = RoadmapScheduleItemStatus.STARTED;
            updatedAt = now;
        }
    }

    public void complete(Instant now) {
        if (status != RoadmapScheduleItemStatus.COMPLETED) {
            status = RoadmapScheduleItemStatus.COMPLETED;
            updatedAt = now;
        }
    }

    public String getId() { return id; }
    public String getScheduleId() { return scheduleId; }
    public String getOwnerId() { return ownerId; }
    public String getUserRoadmapId() { return userRoadmapId; }
    public String getUserRoadmapNodeId() { return userRoadmapNodeId; }
    public String getNodeId() { return nodeId; }
    public LocalDate getScheduledDate() { return scheduledDate; }
    public int getPlannedMinutes() { return plannedMinutes; }
    public RoadmapScheduleItemStatus getStatus() { return status; }
}
