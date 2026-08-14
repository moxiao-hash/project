package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "roadmap_node_check_ins")
public class RoadmapNodeCheckInEntity {
    @Id
    @Column(nullable = false, length = 36)
    private String id;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(name = "user_roadmap_id", nullable = false, length = 36)
    private String userRoadmapId;
    @Column(name = "user_roadmap_node_id", nullable = false, length = 36)
    private String userRoadmapNodeId;
    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;
    @Column(nullable = false, length = 2000)
    private String summary;
    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RoadmapNodeCheckInEntity() { }

    public RoadmapNodeCheckInEntity(
            String id,
            String ownerId,
            String userRoadmapId,
            String userRoadmapNodeId,
            String nodeId,
            String summary,
            String idempotencyKey,
            Instant createdAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.userRoadmapId = userRoadmapId;
        this.userRoadmapNodeId = userRoadmapNodeId;
        this.nodeId = nodeId;
        this.summary = summary;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getUserRoadmapId() { return userRoadmapId; }
    public String getUserRoadmapNodeId() { return userRoadmapNodeId; }
    public String getNodeId() { return nodeId; }
    public String getSummary() { return summary; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
